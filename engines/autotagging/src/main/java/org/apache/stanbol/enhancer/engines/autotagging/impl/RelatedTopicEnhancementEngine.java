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
package org.apache.stanbol.enhancer.engines.autotagging.impl;

import static org.apache.stanbol.enhancer.servicesapi.rdf.Properties.NIE_PLAINTEXTCONTENT;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.clerezza.rdf.core.LiteralFactory;
import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.NonLiteral;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.commons.io.IOUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.stanbol.autotagging.Autotagger;
import org.apache.stanbol.autotagging.TagInfo;
import org.apache.stanbol.enhancer.engines.autotagging.AutotaggerProvider;
import org.apache.stanbol.enhancer.servicesapi.ContentItem;
import org.apache.stanbol.enhancer.servicesapi.EngineException;
import org.apache.stanbol.enhancer.servicesapi.EnhancementEngine;
import org.apache.stanbol.enhancer.servicesapi.InvalidContentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi wrapper for the iks-autotagging library. Uses a lucene index of DBpedia to suggest related related
 * topics out of the text content of the content item.
 *
 * Note: this engine does not works as it requires a dedicated lucene index that does not work yet. It will be
 * replaced by a matching engine that uses the EntityHub instead of the iks-autotaggin lib.
 *
 * @author ogrisel
 */
//@Component(immediate = false, metatype = true)
//@Service
public class RelatedTopicEnhancementEngine implements EnhancementEngine {

    protected static final String TEXT_PLAIN_MIMETYPE = "text/plain";

    private static final Logger log = LoggerFactory.getLogger(RelatedTopicEnhancementEngine.class);

    // TODO: make me configurable through an OSGi property
    protected String type = "http://www.w3.org/2004/02/skos/core#Concept";

    @Reference
    AutotaggerProvider autotaggerProvider;

    public void setType(String type) {
        this.type = type;
    }

    public void computeEnhancements(ContentItem ci) throws EngineException {
        Autotagger autotagger = autotaggerProvider.getAutotagger();
        if (autotagger == null) {
            log.warn(getClass().getSimpleName()
                    + " is deactivated: cannot process content item: "
                    + ci.getId());
            return;
        }
        String mimeType = ci.getMimeType().split(";", 2)[0];
        String text = "";
        if (TEXT_PLAIN_MIMETYPE.equals(mimeType)) {
            try {
                text = IOUtils.toString(ci.getStream(),"UTF-8");
            } catch (IOException e) {
                throw new InvalidContentException(this, ci, e);
            }
        } else {
            Iterator<Triple> it = ci.getMetadata().filter(new UriRef(ci.getId()), NIE_PLAINTEXTCONTENT, null);
            while (it.hasNext()) {
                text += it.next().getObject();
            }
        }
        if (text.trim().length() == 0) {
            // TODO: make the length of the data a field of the ContentItem
            // interface to be able to filter out empty items in the canEnhance
            // method
            log.warn("nothing to extract a topic from");
            return;
        }

        MGraph graph = ci.getMetadata();
        LiteralFactory literalFactory = LiteralFactory.getInstance();
        UriRef contentItemId = new UriRef(ci.getId());
        try {
            List<TagInfo> suggestions = autotagger.suggestForType(text, type);
            Collection<NonLiteral> noRelatedEnhancements = Collections.emptyList();
            for (TagInfo tag : suggestions) {
                EnhancementRDFUtils.writeEntityAnnotation(this, literalFactory,
                        graph, contentItemId,
                        noRelatedEnhancements, tag);
            }
        } catch (IOException e) {
            throw new EngineException(this, ci, e);
        }
    }

    public int canEnhance(ContentItem ci) {
           String mimeType = ci.getMimeType().split(";",2)[0];
        if (TEXT_PLAIN_MIMETYPE.equalsIgnoreCase(mimeType)) {
            return ENHANCE_SYNCHRONOUS;
        }
        // check for existence of textual content in metadata
        UriRef subj = new UriRef(ci.getId());
        Iterator<Triple> it = ci.getMetadata().filter(subj, NIE_PLAINTEXTCONTENT, null);
        if (it.hasNext()) {
            return ENHANCE_SYNCHRONOUS;
        }
        return CANNOT_ENHANCE;
    }

    public void bindAutotaggerProvider(AutotaggerProvider autotaggerProvider) {
        this.autotaggerProvider = autotaggerProvider;
    }
}
