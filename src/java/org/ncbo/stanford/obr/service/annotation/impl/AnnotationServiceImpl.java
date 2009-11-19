package org.ncbo.stanford.obr.service.annotation.impl;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import obs.common.beans.DictionaryBean;
import obs.common.files.FileParameters;
import obs.common.utils.ExecutionTimer;

import org.apache.log4j.Logger;
import org.ncbo.stanford.obr.dao.obs.CommonObsDao;
import org.ncbo.stanford.obr.resource.ResourceAccessTool;
import org.ncbo.stanford.obr.service.AbstractResourceService;
import org.ncbo.stanford.obr.service.annotation.AnnotationService;
import org.ncbo.stanford.obr.util.mgrep.ConceptRecognitionTools;

public class AnnotationServiceImpl extends AbstractResourceService implements
		AnnotationService {

	// Logger for AnnotationServiceImpl
	protected static Logger logger = Logger
			.getLogger(AnnotationServiceImpl.class);

	public AnnotationServiceImpl(ResourceAccessTool resourceAccessTool) {
		super(resourceAccessTool);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.ncbo.stanford.obr.service.annotation.AnnotationService#resourceAnnotation
	 * (boolean, java.util.HashSet)
	 */
	public int resourceAnnotation(boolean withCompleteDictionary,
			HashSet<String> stopwords) {
		int nbAnnotation=0;
		// gets the latest dictionary from OBS_DVT
		DictionaryBean dictionary = commonObsDao.getLastDictionaryBean();

		// processes direct mgrep annotations
		nbAnnotation = this.conceptRecognitionWithMgrep(dictionary,
				withCompleteDictionary, stopwords);

		// processes direct reported annotations
		nbAnnotation += this.reportExistingAnnotations(dictionary);

		// updates the dictionary column in _ET
		logger.info("Updating the dictionary field in ElementTable...");
		elementTableDao.updateDictionary(dictionary.getDictionaryID());
		return nbAnnotation;
	}

	/**
	 * Applies Mgrep on the corresponding resource. Only the elements in _ET
	 * with a dictionaryID < to the latest one are selected (or the one with
	 * null); Returns the number of annotations added to _DAT.
	 */
	private int conceptRecognitionWithMgrep(DictionaryBean dictionary,
			boolean withCompleteDictionary, HashSet<String> stopwords) {
		int nbDirectAnnotation = 0;
		ExecutionTimer timer = new ExecutionTimer();

		logger.info("** Concept recognition with Mgrep:");
		// Checks if the dictionary file exists
		File dictionaryFile;
		try {
			if (withCompleteDictionary) {
				dictionaryFile = new File(CommonObsDao
						.completeDictionaryFileName(dictionary));
			} else {
				dictionaryFile = new File(CommonObsDao
						.dictionaryFileName(dictionary));
			}
			if (dictionaryFile.createNewFile()) {
				logger.info("Re-creation of the dictionaryFile...");
				if (withCompleteDictionary) {
					commonObsDao.writeDictionaryFile(dictionaryFile);
				} else {
					commonObsDao.writeDictionaryFile(dictionaryFile, dictionary
							.getDictionaryID());
				}
			}
		} catch (IOException e) {
			dictionaryFile = null;
			logger
					.error(
							"** PROBLEM ** Cannot create the dictionaryFile. null returned.",
							e);
		}

		// Writes the resource file with the elements not processed with the
		// latest dictionary
		timer.start();
		File resourceFile = this.writeMgrepResourceFile(dictionary
				.getDictionaryID());
		timer.end();
		logger.info("ResourceFile created in: "
				+ timer.millisecondsToTimeString(timer.duration()) + "\n");

		// Calls Mgrep
		timer.reset();
		timer.start();
		File mgrepFile = this.mgrepCall(dictionaryFile, resourceFile);
		timer.end();
		logger.info("Mgrep executed in: "
				+ timer.millisecondsToTimeString(timer.duration()));

		// Process the Mgrep result file
		timer.reset();
		timer.start();
		nbDirectAnnotation = this.processMgrepFile(mgrepFile, dictionary
				.getDictionaryID());
		timer.end();
		logger.info("MgrepFile processed in: "
				+ timer.millisecondsToTimeString(timer.duration()));

		// Deletes the files created for Mgrep and generated by Mgrep
		resourceFile.delete();
		mgrepFile.delete();

		// Removes Mgrep annotations done with the given list of stopwords
		timer.reset();
		timer.start();
		int nbDelete = directAnnotationTableDao
				.deleteEntriesFromStopWords(stopwords);
		timer.end();
		logger.info(nbDelete + " annotations removed with stopword list in: "
				+ timer.millisecondsToTimeString(timer.duration()));

		return nbDirectAnnotation - nbDelete;
	}

	private File mgrepCall(File dictionaryFile, File resourceFile) {
		logger.info("Call to Mgrep...");
		File mgrepFile = null;
		try {
			mgrepFile = ConceptRecognitionTools.mgrepLocal(dictionaryFile,
					resourceFile);
		} catch (IOException e) {
			logger.error("** PROBLEM ** Cannot create MgrepFile.", e);
		} catch (Exception e) {
			logger.error("** PROBLEM ** Cannot execute Mgrep.", e);
		}

		return mgrepFile;

	}

	private int processMgrepFile(File mgrepFile, int dictionaryID) {
		int nbAnnotation = -1;
		logger.info("Processing of the result file...");
		nbAnnotation = directAnnotationTableDao.loadMgrepFile(mgrepFile,
				dictionaryID);
		logger.info(nbAnnotation + " annotations done with Mgrep.");
		return nbAnnotation;
	}

	/********************************* EXPORT CONTENT FUNCTIONS *****************************************************/

	/**
	 * Returns a file that respects the Mgrep resource file requirements. This
	 * text file has 3 columns: [integer | integer | text] they are respectively
	 * [elementID | contextID | text]. The file contains only the element that
	 * have been already annotated with a previous version of the given
	 * dictionary (or never annotated).
	 */
	public File writeMgrepResourceFile(int dictionaryID) {
		logger
				.info("Exporting the resource content to a file to be annotated with Mgrep...");
		String name = FileParameters.mgrepInputFolder()
				+ ResourceAccessTool.RESOURCE_NAME_PREFIX
				+ resourceAccessTool.getToolResource().getResourceID() + "_V"
				+ dictionaryID + "_MGREP.txt";
		File mgrepResourceFile = new File(name);
		try {
			mgrepResourceFile.createNewFile();
			elementTableDao.writeNonAnnotatedElements(mgrepResourceFile,
					dictionaryID, resourceAccessTool.getToolResource()
							.getResourceStructure());
		} catch (IOException e) {
			logger.error(
					"** PROBLEM ** Cannot create Mgrep file for exporting resource "
							+ resourceAccessTool.getToolResource()
									.getResourceName(), e);
		}
		return mgrepResourceFile;
	}

	/**
	 * For annotations with concepts from ontologies that already exist in the
	 * resource, annotations are reported to the _ET table in the form of
	 * localConceptIDs separated by '> '. This function transfers the reported
	 * annotation into the corresponding _DAT table in order for them to be
	 * available in the same format and to be processed by the rest of the
	 * workflow (semantic expansion). It use the dictionaryID of the given
	 * dictionary. Returns the number of reported annotations added to _DAT.
	 */
	private int reportExistingAnnotations(DictionaryBean dictionary) {
		int nbReported;
		ExecutionTimer timer = new ExecutionTimer();

		logger.info("Processing of existing reported annotations...");
		timer.start();
		nbReported = directAnnotationTableDao.addEntries(elementTableDao
				.getExistingAnnotations(dictionary.getDictionaryID(),
						resourceAccessTool.getToolResource()
								.getResourceStructure()));

		timer.end();
		logger.info(nbReported + " reported annotations processed in: "
				+ timer.millisecondsToTimeString(timer.duration()));

		return nbReported;
	}

}
