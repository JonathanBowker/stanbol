/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.stanbol.enhancer.engines.keywordextraction.engine;

import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.PHRASE_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.POS_ANNOTATION;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.DC_CREATOR;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.DC_LANGUAGE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_CONFIDENCE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.ENHANCER_EXTRACTED_FROM;
import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.RDF_TYPE;
import static org.apache.stanbol.enhancer.servicesapi.rdf.TechnicalClasses.ENHANCER_ENTITYANNOTATION;
import static org.apache.stanbol.enhancer.test.helper.EnhancementStructureHelper.validateAllTextAnnotations;
import static org.apache.stanbol.enhancer.test.helper.EnhancementStructureHelper.validateEntityAnnotation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import opennlp.tools.tokenize.SimpleTokenizer;

import org.apache.clerezza.rdf.core.Literal;
import org.apache.clerezza.rdf.core.LiteralFactory;
import org.apache.clerezza.rdf.core.Resource;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.TypedLiteral;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.impl.PlainLiteralImpl;
import org.apache.clerezza.rdf.core.impl.TripleImpl;
import org.apache.stanbol.commons.opennlp.OpenNLP;
import org.apache.stanbol.enhancer.contentitem.inmemory.InMemoryContentItemFactory;
import org.apache.stanbol.enhancer.engines.keywordextraction.engine.KeywordLinkingEngine;
import org.apache.stanbol.enhancer.engines.keywordextraction.impl.EntityLinker;
import org.apache.stanbol.enhancer.engines.keywordextraction.impl.LinkedEntity;
import org.apache.stanbol.enhancer.engines.keywordextraction.impl.Suggestion;
import org.apache.stanbol.enhancer.engines.keywordextraction.impl.TestSearcherImpl;
import org.apache.stanbol.enhancer.engines.keywordextraction.linking.EntityLinkerConfig;
import org.apache.stanbol.enhancer.engines.keywordextraction.linking.EntityLinkerConfig.RedirectProcessingMode;
import org.apache.stanbol.enhancer.engines.keywordextraction.linking.TextProcessingConfig;
import org.apache.stanbol.enhancer.engines.keywordextraction.linking.impl.OpenNlpLabelTokenizer;
import org.apache.stanbol.enhancer.nlp.model.AnalysedText;
import org.apache.stanbol.enhancer.nlp.model.AnalysedTextFactory;
import org.apache.stanbol.enhancer.nlp.model.AnalysedTextUtils;
import org.apache.stanbol.enhancer.nlp.model.annotation.Value;
import org.apache.stanbol.enhancer.nlp.phrase.PhraseTag;
import org.apache.stanbol.enhancer.nlp.pos.LexicalCategory;
import org.apache.stanbol.enhancer.nlp.pos.Pos;
import org.apache.stanbol.enhancer.nlp.pos.PosTag;
import org.apache.stanbol.enhancer.servicesapi.ContentItem;
import org.apache.stanbol.enhancer.servicesapi.ContentItemFactory;
import org.apache.stanbol.enhancer.servicesapi.EngineException;
import org.apache.stanbol.enhancer.servicesapi.impl.StringSource;
import org.apache.stanbol.enhancer.servicesapi.rdf.OntologicalClasses;
import org.apache.stanbol.enhancer.servicesapi.rdf.Properties;
import org.apache.stanbol.enhancer.test.helper.EnhancementStructureHelper;
import org.apache.stanbol.entityhub.core.model.InMemoryValueFactory;
import org.apache.stanbol.entityhub.servicesapi.defaults.NamespaceEnum;
import org.apache.stanbol.entityhub.servicesapi.model.Representation;
import org.apache.stanbol.entityhub.servicesapi.model.ValueFactory;
import org.apache.stanbol.entityhub.servicesapi.model.rdf.RdfResourceEnum;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: convert this to an integration test!
 * @author Rupert Westenthaler
 */
public class KeywordLinkingEngineTest {
    
    private final static Logger log = LoggerFactory.getLogger(KeywordLinkingEngineTest.class);

    /**
     * The context for the tests (same as in TestOpenNLPEnhancementEngine)
     */
    public static final String TEST_TEXT = "Dr. Patrick Marshall (1869 - November 1950) was a"
        + " geologist who lived in New Zealand and worked at the University of Otago.";
    
    private static AnalysedText TEST_ANALYSED_TEXT;
    
//    public static final String TEST_TEXT2 = "A CBS televised debate between Australia's " +
//    		"candidates for Prime Minister in the upcoming US election has been rescheduled " +
//    		"and shortend, to avoid a clash with popular cookery sow MasterChef.";
    
    private static final ContentItemFactory ciFactory = InMemoryContentItemFactory.getInstance();
    
    private static final String TEST_REFERENCED_SITE_NAME = "dummRefSiteName";
    
    static TestSearcherImpl searcher;
    static ValueFactory factory = InMemoryValueFactory.getInstance();
        
    public static final String NAME = NamespaceEnum.rdfs+"label";
    public static final String TYPE = NamespaceEnum.rdf+"type";
    public static final String REDIRECT = NamespaceEnum.rdfs+"seeAlso";

    @BeforeClass
    public static void setUpServices() throws IOException {
        searcher = new TestSearcherImpl(NAME,SimpleTokenizer.INSTANCE);
        //add some terms to the searcher
        Representation rep = factory.createRepresentation("urn:test:PatrickMarshall");
        rep.addNaturalText(NAME, "Patrick Marshall");
        rep.addReference(TYPE, OntologicalClasses.DBPEDIA_PERSON.getUnicodeString());
        searcher.addEntity(rep);
        rep = factory.createRepresentation("urn:test:Geologist");
        rep.addNaturalText(NAME, "Geologist");
        rep.addReference(TYPE, NamespaceEnum.skos+"Concept");
        rep.addReference(REDIRECT, "urn:test:redirect:Geologist");
        searcher.addEntity(rep);
        //a redirect
        rep = factory.createRepresentation("urn:test:redirect:Geologist");
        rep.addNaturalText(NAME, "Geologe (redirect)");
        rep.addReference(TYPE, NamespaceEnum.skos+"Concept");
        searcher.addEntity(rep);
        rep = factory.createRepresentation("urn:test:NewZealand");
        rep.addNaturalText(NAME, "New Zealand");
        rep.addReference(TYPE, OntologicalClasses.DBPEDIA_PLACE.getUnicodeString());
        searcher.addEntity(rep);
        rep = factory.createRepresentation("urn:test:UniversityOfOtago");
        rep.addNaturalText(NAME, "University of Otago");
        rep.addReference(TYPE, OntologicalClasses.DBPEDIA_ORGANISATION.getUnicodeString());
        searcher.addEntity(rep);
        rep = factory.createRepresentation("urn:test:University");
        rep.addNaturalText(NAME, "University");
        rep.addReference(TYPE, NamespaceEnum.skos+"Concept");
        searcher.addEntity(rep);
        rep = factory.createRepresentation("urn:test:Otago");
        rep.addNaturalText(NAME, "Otago");
        rep.addReference(TYPE, OntologicalClasses.DBPEDIA_PLACE.getUnicodeString());
        searcher.addEntity(rep);
        //add a 2nd Otago (Place and University
        rep = factory.createRepresentation("urn:test:Otago_Texas");
        rep.addNaturalText(NAME, "Otago (Texas)");
        rep.addNaturalText(NAME, "Otago");
        rep.addReference(TYPE, OntologicalClasses.DBPEDIA_PLACE.getUnicodeString());
        searcher.addEntity(rep);
        rep = factory.createRepresentation("urn:test:UniversityOfOtago_Texas");
        rep.addNaturalText(NAME, "University of Otago (Texas)");
        rep.addReference(TYPE, OntologicalClasses.DBPEDIA_ORGANISATION.getUnicodeString());
        searcher.addEntity(rep);
        
        Value<PhraseTag> nounPhrase = Value.value(new PhraseTag("NP",LexicalCategory.Noun),1d);
        TEST_ANALYSED_TEXT = AnalysedTextFactory.getDefaultInstance().createAnalysedText(
                ciFactory.createBlob(new StringSource(TEST_TEXT)));
        TEST_ANALYSED_TEXT.addSentence(0, TEST_ANALYSED_TEXT.getEnd());
        //add some noun phrases
        TEST_ANALYSED_TEXT.addChunk(0, "Dr. Patrick Marshall".length()).addAnnotation(PHRASE_ANNOTATION, nounPhrase);
        TEST_ANALYSED_TEXT.addChunk(TEST_TEXT.indexOf("New Zealand"), TEST_TEXT.indexOf("New Zealand")+"New Zealand".length())
        .addAnnotation(PHRASE_ANNOTATION, nounPhrase);
        TEST_ANALYSED_TEXT.addChunk(TEST_TEXT.indexOf("geologist"), TEST_TEXT.indexOf("geologist")+"geologist".length())
        .addAnnotation(PHRASE_ANNOTATION, nounPhrase);
        TEST_ANALYSED_TEXT.addChunk(TEST_TEXT.indexOf("the University of Otago"), 
            TEST_TEXT.length()-1).addAnnotation(PHRASE_ANNOTATION, nounPhrase);
        //add some tokens
        TEST_ANALYSED_TEXT.addToken(0, 2).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NE",Pos.Abbreviation),1d));
        TEST_ANALYSED_TEXT.addToken(2, 3).addAnnotation(POS_ANNOTATION, Value.value(new PosTag(".",Pos.Point),1d));
        TEST_ANALYSED_TEXT.addToken(4, 11).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NP",Pos.ProperNoun),1d));
        TEST_ANALYSED_TEXT.addToken(12, 20).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NP",Pos.ProperNoun),1d));
        int start = TEST_TEXT.indexOf("(1869 - November 1950)");
        TEST_ANALYSED_TEXT.addToken(start,start+1).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("(",Pos.OpenBracket),1d));
        TEST_ANALYSED_TEXT.addToken(start+1,start+5).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NUM",Pos.Numeral),1d));
        TEST_ANALYSED_TEXT.addToken(start+6,start+7).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("-",Pos.Hyphen),1d));
        TEST_ANALYSED_TEXT.addToken(start+8,start+16).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NE",Pos.CommonNoun),1d));
        TEST_ANALYSED_TEXT.addToken(start+17,start+21).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NUM",Pos.Numeral),1d));
        TEST_ANALYSED_TEXT.addToken(start+21,start+22).addAnnotation(POS_ANNOTATION, Value.value(new PosTag(")",Pos.CloseBracket),1d));
                
        start = TEST_TEXT.indexOf("geologist");
        TEST_ANALYSED_TEXT.addToken(start,start+9).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NE",Pos.CommonNoun),1d));
        
        start = TEST_TEXT.indexOf("New Zealand");
        TEST_ANALYSED_TEXT.addToken(start,start+3).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NE",Pos.CommonNoun),1d));
        TEST_ANALYSED_TEXT.addToken(start+4,start+11).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NP",Pos.ProperNoun),1d));
        
        start = TEST_TEXT.indexOf("the University of Otago");
        TEST_ANALYSED_TEXT.addToken(start,start+3).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("ART",Pos.Article),1d));
        TEST_ANALYSED_TEXT.addToken(start+4,start+14).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NE",Pos.CommonNoun),1d));
        TEST_ANALYSED_TEXT.addToken(start+15,start+17).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("OF",LexicalCategory.PronounOrDeterminer),1d));
        TEST_ANALYSED_TEXT.addToken(start+18,start+23).addAnnotation(POS_ANNOTATION, Value.value(new PosTag("NP",Pos.ProperNoun),1d));
        TEST_ANALYSED_TEXT.addToken(start+23,start+24).addAnnotation(POS_ANNOTATION, Value.value(new PosTag(".",Pos.Point),1d));
        
    }

    @Before
    public void bindServices() throws IOException {
    }

    @After
    public void unbindServices() {
    }

    @AfterClass
    public static void shutdownServices() {
    }

    public static ContentItem getContentItem(final String id, final String text) throws IOException {
        return ciFactory.createContentItem(new UriRef(id),new StringSource(text));
    }
    /**
     * This tests the EntityLinker functionality (if the expected Entities
     * are linked). In this case with the default configurations for
     * {@link LexicalCategory#Noun}.
     * @throws Exception
     */
    @Test
    public void testEntityLinkerWithNouns() throws Exception {
        TextProcessingConfig tpc = new TextProcessingConfig();
        tpc.setProcessedLexicalCategories(KeywordLinkingEngine.DEFAULT_PROCESSED_LEXICAL_CATEGORIES);
        tpc.setProcessedPos(Collections.EMPTY_SET);
        EntityLinkerConfig config = new EntityLinkerConfig();
        config.setRedirectProcessingMode(RedirectProcessingMode.FOLLOW);
        EntityLinker linker = new EntityLinker(TEST_ANALYSED_TEXT,"en",
            tpc, searcher, config, new OpenNlpLabelTokenizer(null));
        linker.process();
        Map<String,List<String>> expectedResults = new HashMap<String,List<String>>();
        expectedResults.put("Patrick Marshall", new ArrayList<String>(
                Arrays.asList("urn:test:PatrickMarshall")));
        expectedResults.put("geologist", new ArrayList<String>(
                Arrays.asList("urn:test:redirect:Geologist"))); //the redirected entity
        expectedResults.put("New Zealand", new ArrayList<String>(
                Arrays.asList("urn:test:NewZealand")));
        expectedResults.put("University of Otago", new ArrayList<String>(
                Arrays.asList("urn:test:UniversityOfOtago","urn:test:UniversityOfOtago_Texas")));
        validateEntityLinkerResults(linker, expectedResults);
    }
    /**
     * This tests the EntityLinker functionality (if the expected Entities
     * are linked). In this case with the default configurations for
     * {@link Pos#ProperNoun}.
     * @throws Exception
     */
    @Test
    public void testEntityLinkerWithProperNouns() throws Exception {
        TextProcessingConfig tpc = new TextProcessingConfig();
        tpc.setProcessedLexicalCategories(Collections.EMPTY_SET);
        tpc.setProcessedPos(KeywordLinkingEngine.DEFAULT_PROCESSED_POS_TYPES);
        EntityLinkerConfig config = new EntityLinkerConfig();
        config.setRedirectProcessingMode(RedirectProcessingMode.FOLLOW);
        EntityLinker linker = new EntityLinker(TEST_ANALYSED_TEXT,"en",
            tpc, searcher, config, new OpenNlpLabelTokenizer(null));
        linker.process();
        Map<String,List<String>> expectedResults = new HashMap<String,List<String>>();
        expectedResults.put("Patrick Marshall", new ArrayList<String>(
                Arrays.asList("urn:test:PatrickMarshall")));
        //Geologist is a common noun and MUST NOT be found
        //expectedResults.put("geologist", new ArrayList<String>(
        //        Arrays.asList("urn:test:redirect:Geologist"))); //the redirected entity
        expectedResults.put("New Zealand", new ArrayList<String>(
                Arrays.asList("urn:test:NewZealand")));
        expectedResults.put("University of Otago", new ArrayList<String>(
                Arrays.asList("urn:test:UniversityOfOtago","urn:test:UniversityOfOtago_Texas")));
        validateEntityLinkerResults(linker, expectedResults);
    }
    private void validateEntityLinkerResults(EntityLinker linker, Map<String,List<String>> expectedResults) {
        log.info("---------------------");
        log.info("- Validating Results-");
        log.info("---------------------");
        for(LinkedEntity linkedEntity : linker.getLinkedEntities().values()){
            log.info("> LinkedEntity {}",linkedEntity);
            List<String> expectedSuggestions = expectedResults.remove(linkedEntity.getSelectedText());
            assertNotNull("LinkedEntity '"+linkedEntity.getSelectedText()+
                "' is not an expected Result (or was found twice)", expectedSuggestions);
            linkedEntity.getSuggestions().iterator();
            assertEquals("Number of suggestions "+linkedEntity.getSuggestions().size()+
                " != number of expected suggestions "+expectedSuggestions.size()+
                "for selection "+linkedEntity.getSelectedText(), 
                linkedEntity.getSuggestions().size(),
                expectedSuggestions.size());
            double score = linkedEntity.getScore();
            for(int i=0;i<expectedSuggestions.size();i++){
                Suggestion suggestion = linkedEntity.getSuggestions().get(i);
                assertEquals("Expecced Suggestion at Rank "+i+" expected: "+
                    expectedSuggestions.get(i)+" suggestion: "+
                    suggestion.getRepresentation().getId(),
                    expectedSuggestions.get(i), 
                    suggestion.getRepresentation().getId());
                assertTrue("Score of suggestion "+i+"("+suggestion.getScore()+
                    " > as of the previous one ("+score+")",
                    score >= suggestion.getScore());
                score = suggestion.getScore();
            }
        }
        Assert.assertTrue("The expected Result(s) "+expectedResults+" wehre not found",
            expectedResults.isEmpty());
    }
    /**
     * This tests if the Enhancements created by the Engine confirm to the
     * rules defined for the Stanbol Enhancement Structure.
     * @throws IOException
     * @throws EngineException
     */
    @Test
    public void testEngine() throws IOException, EngineException {
        EntityLinkerConfig linkerConfig = new EntityLinkerConfig();
        linkerConfig.setRedirectProcessingMode(RedirectProcessingMode.FOLLOW);
        KeywordLinkingEngine engine = KeywordLinkingEngine.createInstance(searcher, new TextProcessingConfig(), 
            linkerConfig, new OpenNlpLabelTokenizer());
        engine.referencedSiteName = TEST_REFERENCED_SITE_NAME;
        ContentItem ci = ciFactory.createContentItem(new StringSource(TEST_TEXT));
        //tells the engine that this is an English text
        ci.getMetadata().add(new TripleImpl(ci.getUri(), DC_LANGUAGE, new PlainLiteralImpl("en")));
        //and add the AnalysedText instance used for this test
        ci.addPart(AnalysedText.ANALYSED_TEXT_URI, TEST_ANALYSED_TEXT);
        //compute the enhancements
        engine.computeEnhancements(ci);
        //validate the enhancement results
        Map<UriRef,Resource> expectedValues = new HashMap<UriRef,Resource>();
        expectedValues.put(ENHANCER_EXTRACTED_FROM, ci.getUri());
        expectedValues.put(DC_CREATOR,LiteralFactory.getInstance().createTypedLiteral(
            engine.getClass().getName()));
        //adding null as expected for confidence makes it a required property
        expectedValues.put(Properties.ENHANCER_CONFIDENCE, null);
        //validate create fise:TextAnnotations
        int numTextAnnotations = validateAllTextAnnotations(ci.getMetadata(), TEST_TEXT, expectedValues);
        assertEquals("Four fise:TextAnnotations are expected by this Test", 4, numTextAnnotations);
        //validate create fise:EntityAnnotations
        int numEntityAnnotations = validateAllEntityAnnotations(ci, expectedValues);
        assertEquals("Five fise:EntityAnnotations are expected by this Test", 5, numEntityAnnotations);
    }
    /**
     * Similar to {@link EnhancementStructureHelper#validateAllEntityAnnotations(org.apache.clerezza.rdf.core.TripleCollection, Map)}
     * but in addition checks fise:confidence [0..1] and entityhub:site properties
     * @param ci
     * @param expectedValues
     * @return
     */
    private static int validateAllEntityAnnotations(ContentItem ci, Map<UriRef,Resource> expectedValues){
        Iterator<Triple> entityAnnotationIterator = ci.getMetadata().filter(null,
                RDF_TYPE, ENHANCER_ENTITYANNOTATION);
        int entityAnnotationCount = 0;
        while (entityAnnotationIterator.hasNext()) {
            UriRef entityAnnotation = (UriRef) entityAnnotationIterator.next().getSubject();
            // test if selected Text is added
            validateEntityAnnotation(ci.getMetadata(), entityAnnotation, expectedValues);
            //validate also that the confidence is between [0..1]
            Iterator<Triple> confidenceIterator = ci.getMetadata().filter(entityAnnotation, ENHANCER_CONFIDENCE, null);
            //Confidence is now checked by the EnhancementStructureHelper (STANBOL-630)
//            assertTrue("Expected fise:confidence value is missing (entityAnnotation "
//                    +entityAnnotation+")",confidenceIterator.hasNext());
//            Double confidence = LiteralFactory.getInstance().createObject(Double.class,
//                (TypedLiteral)confidenceIterator.next().getObject());
//            assertTrue("fise:confidence MUST BE <= 1 (value= '"+confidence
//                    + "',entityAnnotation " +entityAnnotation+")",
//                    1.0 >= confidence.doubleValue());
//            assertTrue("fise:confidence MUST BE >= 0 (value= '"+confidence
//                    +"',entityAnnotation "+entityAnnotation+")",
//                    0.0 <= confidence.doubleValue());
            //Test the entityhub:site property (STANBOL-625)
            UriRef ENTITYHUB_SITE = new UriRef(RdfResourceEnum.site.getUri());
            Iterator<Triple> entitySiteIterator = ci.getMetadata().filter(entityAnnotation, 
                ENTITYHUB_SITE, null);
            assertTrue("Expected entityhub:site value is missing (entityAnnotation "
                    +entityAnnotation+")",entitySiteIterator.hasNext());
            Resource siteResource = entitySiteIterator.next().getObject();
            assertTrue("entityhub:site values MUST BE Literals", siteResource instanceof Literal);
            assertEquals("'"+TEST_REFERENCED_SITE_NAME+"' is expected as "
                + "entityhub:site value", TEST_REFERENCED_SITE_NAME, 
                ((Literal)siteResource).getLexicalForm());
            assertFalse("entityhub:site MUST HAVE only a single value", entitySiteIterator.hasNext());
            entityAnnotationCount++;
        }
        return entityAnnotationCount;
        
    }
}