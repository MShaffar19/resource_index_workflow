package org.ncbo.stanford.obr.service.annotation;

import java.util.HashSet;
import java.util.List;

import obs.common.beans.DictionaryBean;

/**
 * Service for processing direct annotations
 * 
 * @author Kuladip Yadav
 *
 */
public interface AnnotationService {
   
	/**
	 * 
	 * Processes the resource with Mgrep and populates the the corresponding _DAT.
	 * The given boolean specifies if the complete dictionary must be used, or not (only the delta dictionary).
	 * The annotation done with termName that are in the specified stopword list are removed from _DAT.
	 * This function implements the step 2 of the OBR workflow.
	 * 
	 * @param withCompleteDictionary if true uses complete dictionary for annotation otherwise uses delta dictionary
	 * @param dictionary Latest dictionary bean
	 * @param stopwords {@code Set} of string used as stopwords
	 * @return {@code int} the number of direct annotations created. 
	 */
	public int resourceAnnotation(boolean withCompleteDictionary, DictionaryBean dictionary, HashSet<String> stopwords);
	
	/**
	 * Method removes annotations for given ontology versions.
	 * 
	 * @param {@code List} of localOntologyIDs String containing ontology versions.
	 */
	public void removeAnnotations(List<String> localOntologyIDs);
	
	/**
	 * This method creates temporary element table used for annotation for non annotated
	 * element for given dictionary id.
	 * 
	 * @param dictionaryID 
	 * @return Number of rows containing in temporary table
	 */
	public int createTemporaryElementTable(int dictionaryID);
}
