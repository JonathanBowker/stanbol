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
package org.apache.stanbol.entityhub.yard.solr.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.MoreLikeThisParams;
import org.apache.stanbol.commons.solr.utils.SolrUtil;
import org.apache.stanbol.entityhub.core.model.InMemoryValueFactory;
import org.apache.stanbol.entityhub.core.query.DefaultQueryFactory;
import org.apache.stanbol.entityhub.servicesapi.model.Representation;
import org.apache.stanbol.entityhub.servicesapi.model.ValueFactory;
import org.apache.stanbol.entityhub.servicesapi.query.Constraint;
import org.apache.stanbol.entityhub.servicesapi.query.FieldQuery;
import org.apache.stanbol.entityhub.servicesapi.query.Query;
import org.apache.stanbol.entityhub.servicesapi.query.RangeConstraint;
import org.apache.stanbol.entityhub.servicesapi.query.SimilarityConstraint;
import org.apache.stanbol.entityhub.servicesapi.query.TextConstraint;
import org.apache.stanbol.entityhub.servicesapi.query.ValueConstraint;
import org.apache.stanbol.entityhub.servicesapi.query.Constraint.ConstraintType;
import org.apache.stanbol.entityhub.servicesapi.util.ModelUtils;
import org.apache.stanbol.entityhub.yard.solr.defaults.IndexDataTypeEnum;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.AssignmentEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.DataTypeEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.FieldEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.GeEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.GtEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.LangEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.LeEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.LtEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.RegexEncoder;
import org.apache.stanbol.entityhub.yard.solr.impl.queryencoders.WildcardEncoder;
import org.apache.stanbol.entityhub.yard.solr.model.FieldMapper;
import org.apache.stanbol.entityhub.yard.solr.model.IndexDataType;
import org.apache.stanbol.entityhub.yard.solr.model.IndexValue;
import org.apache.stanbol.entityhub.yard.solr.model.IndexValueFactory;
import org.apache.stanbol.entityhub.yard.solr.model.NoConverterException;
import org.apache.stanbol.entityhub.yard.solr.query.ConstraintTypePosition;
import org.apache.stanbol.entityhub.yard.solr.query.EncodedConstraintParts;
import org.apache.stanbol.entityhub.yard.solr.query.IndexConstraintTypeEncoder;
import org.apache.stanbol.entityhub.yard.solr.query.IndexConstraintTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible of converting the queries used by Entityhub to queries that can be executed via
 * the Solr RESTfull interface.
 * <p>
 * For this conversion the {@link IndexValueFactory} and the {@link FieldMapper} as used to index the
 * documents in the index must be parsed.
 * <p>
 * TODO: This class currently contains the
 * <ul>
 * <li>general usable functionality to convert {@link Query} instances to the according representation in
 * index constraints (see {@link IndexConstraintTypeEnum} and {@link IndexConstraint}
 * <li>general usable functionality to combine the constraints to an tree of AND and OR constraints
 * <li>SolrSpecific configuration of {@link IndexConstraintTypeEncoder}. This need to be made generic to allow
 * different encoder implementations for other Document Stores
 * <li>the Solr Specific encodings of the AND and OR tree
 * </ul>
 * Splitting such things up in several different components should make it easy to add support for other
 * DocumentStores!
 * 
 * @author Rupert Westenthaler
 * 
 */
public class SolrQueryFactory {

    public static final String MLT_QUERY_TYPE = "/" + MoreLikeThisParams.MLT;
    /**
     * Allows to limit the maximum Numbers of Query Results for any kind of Query. For now it is set to 1024.
     */
    public static final Integer MAX_QUERY_RESULTS = 1024;
    /**
     * The default limit of results for queries
     */
    public static final Integer DEFAULT_QUERY_RESULTS = 10;
    private final Logger log = LoggerFactory.getLogger(SolrQueryFactory.class);
    private final FieldMapper fieldMapper;
    private final IndexValueFactory indexValueFactory;
    private final ValueFactory valueFactory;
    private final Map<IndexConstraintTypeEnum,IndexConstraintTypeEncoder<?>> constraintEncoders;

    private String domain;
    private Integer maxQueryResults = MAX_QUERY_RESULTS;
    private Integer defaultQueryResults = DEFAULT_QUERY_RESULTS;

    public SolrQueryFactory(ValueFactory valueFactory,
                            IndexValueFactory indexValueFactory,
                            FieldMapper fieldMapper) {
        if (fieldMapper == null) {
            throw new IllegalArgumentException("The parsed FieldMapper MUST NOT be NULL!");
        }
        if (indexValueFactory == null) {
            throw new IllegalArgumentException("The parsed IndexValueFactory MUST NOT be NULL!");
        }
        if (valueFactory == null) {
            throw new IllegalArgumentException("The parsed ValueFactory MUST NOT be NULL");
        }
        this.valueFactory = valueFactory;
        this.fieldMapper = fieldMapper;
        this.indexValueFactory = indexValueFactory;
        this.constraintEncoders = new HashMap<IndexConstraintTypeEnum,IndexConstraintTypeEncoder<?>>();
        // TODO: Make this configuration more flexible!
        constraintEncoders.put(IndexConstraintTypeEnum.LANG, new LangEncoder(fieldMapper));
        constraintEncoders.put(IndexConstraintTypeEnum.DATATYPE, new DataTypeEncoder(indexValueFactory,
                fieldMapper));
        constraintEncoders.put(IndexConstraintTypeEnum.FIELD, new FieldEncoder(fieldMapper));
        constraintEncoders.put(IndexConstraintTypeEnum.EQ, new AssignmentEncoder(indexValueFactory));
        constraintEncoders.put(IndexConstraintTypeEnum.WILDCARD, new WildcardEncoder(indexValueFactory));
        constraintEncoders.put(IndexConstraintTypeEnum.REGEX, new RegexEncoder(indexValueFactory));
        constraintEncoders.put(IndexConstraintTypeEnum.GE, new GeEncoder(indexValueFactory));
        constraintEncoders.put(IndexConstraintTypeEnum.LE, new LeEncoder(indexValueFactory));
        constraintEncoders.put(IndexConstraintTypeEnum.GT, new GtEncoder(indexValueFactory));
        constraintEncoders.put(IndexConstraintTypeEnum.LT, new LtEncoder(indexValueFactory));
    }

    public enum SELECT {
        ID,
        QUERY,
        ALL
    }

    public SolrQuery parseFieldQuery(FieldQuery fieldQuery, SELECT select) {
        SolrQuery query = initSolrQuery(fieldQuery);
        setSelected(query, fieldQuery.getSelectedFields(), select);
        StringBuilder queryString = new StringBuilder();
        for (Entry<String,Constraint> fieldConstraint : fieldQuery) {
            if (fieldConstraint.getValue().getType() == ConstraintType.similarity) {
                // TODO: log make the FieldQuery ensure that there is no more than one instead of similarity
                // constraint per query
                List<String> fields = Arrays.asList(fieldConstraint.getKey());
                SimilarityConstraint simConstraint = (SimilarityConstraint) fieldConstraint.getValue();
                IndexValue indexValue = indexValueFactory.createIndexValue(simConstraint.getContext());
                fields.addAll(simConstraint.getAdditionalFields());
                query.setQueryType(MLT_QUERY_TYPE);
                query.set(MoreLikeThisParams.MATCH_INCLUDE, false);
                query.set(MoreLikeThisParams.MIN_DOC_FREQ, 1);
                query.set(MoreLikeThisParams.MIN_TERM_FREQ, 1);
                query.set(MoreLikeThisParams.INTERESTING_TERMS, "details");

                // TODO: right now we ignore the fields and fallback to the hardcoded "_text" field
                //Collection<String> mappedFields = fieldMapper.getFieldNames(fields, indexValue);
                //query.set(MoreLikeThisParams.SIMILARITY_FIELDS, StringUtils.join(mappedFields, ","));
                query.set(MoreLikeThisParams.SIMILARITY_FIELDS, "_text");
                query.set(CommonParams.STREAM_BODY, indexValue.getValue());
            } else {
                IndexConstraint indexConstraint = createIndexConstraint(fieldConstraint);
                if (indexConstraint.isInvalid()) {
                    log.warn(String
                            .format(
                                "Unable to create IndexConstraint for Constraint %s (type: %s) and Field %s (Reosens: %s)",
                                fieldConstraint.getValue(), fieldConstraint.getValue().getType(),
                                fieldConstraint.getKey(), indexConstraint.getInvalidMessages()));
                } else {
                    if (queryString.length() > 0) {
                        queryString.append(" AND ");
                    }
                    indexConstraint.encode(queryString);
                }
            }
        }
        if (queryString.length() > 0) {
            String qs = queryString.toString();
            log.info("QueryString: " + qs);
            if (MLT_QUERY_TYPE.equals(query.getQueryType())) {
                query.set(CommonParams.FQ, qs);
            } else {
                query.setQuery(qs);
            }
        }
        return query;
    }

    /**
     * TODO: Currently I have no Idea how to determine all the fields to be selected, because There are any
     * number of possibilities for field names in the index (different data types, different languages ...).
     * Therefore currently I select all fields and apply the filter when converting the {@link SolrDocument}s
     * in the result to the {@link Representation}.
     * <p>
     * The only thing I can do is to select only the ID if an empty list is parsed as selected.
     * 
     * @param query
     * @param selected
     */
    private void setSelected(SolrQuery query, Collection<String> selected, SELECT select) {
        switch (select) {
            case ID:
                query.addField(fieldMapper.getDocumentIdField());
                break;
            case QUERY:
                if (selected.isEmpty()) {
                    query.addField(fieldMapper.getDocumentIdField());
                } else {
                    query.addField("*");
                    // See to do in java doc of this method
                    // for(String field : selected){
                    // if(field != null && !field.isEmpty()){
                    // fieldMapper.getFieldNames(new IndexField(Arrays.asList(field), null, null));
                    // }
                    // }
                }
                break;
            case ALL:
                query.addField("*");
                break;
            default:
                throw new IllegalArgumentException(
                        String.format(
                            "Unknown SELECT status %s! Adapt this implementation to the new value of the Enumeration",
                            select));
        }
        // add the select for the score
        query.addField("score");
    }

    private IndexConstraint createIndexConstraint(Entry<String,Constraint> fieldConstraint) {
        IndexConstraint indexConstraint = new IndexConstraint(Arrays.asList(fieldConstraint.getKey()));
        switch (fieldConstraint.getValue().getType()) {
            case value:
                initIndexConstraint(indexConstraint, (ValueConstraint) fieldConstraint.getValue());
                break;
            case text:
                initIndexConstraint(indexConstraint, (TextConstraint) fieldConstraint.getValue());
                break;
            case range:
                initIndexConstraint(indexConstraint, (RangeConstraint) fieldConstraint.getValue());
                break;
            case similarity:
                // handled by the caller directly pass
                break;
            default:
                indexConstraint.setInvalid(String.format("ConstraintType %s not supported by!",
                    fieldConstraint.getValue().getType()));

        }
        return indexConstraint;
    }

    /**
     * @param indexConstraint
     * @param rangeConstraint
     */
    private void initIndexConstraint(IndexConstraint indexConstraint, RangeConstraint rangeConstraint) {
        // we need to find the Index DataType for the range query
        IndexDataType dataType = null;
        if (rangeConstraint.getLowerBound() != null) {
            dataType = indexValueFactory.createIndexValue(rangeConstraint.getLowerBound()).getType();
        }
        if (rangeConstraint.getUpperBound() != null) {
            IndexDataType upperDataType = indexValueFactory.createIndexValue(rangeConstraint.getUpperBound())
                    .getType();
            if (dataType == null) {
                dataType = upperDataType;
            } else {
                if (!dataType.equals(upperDataType)) {
                    indexConstraint
                            .setInvalid(String
                                    .format(
                                        "A Range Query MUST use the same data type for the upper and lover Bound! (lower:[value=%s|datatype=%s] | upper:[value=%s|datatype=%s])",
                                        rangeConstraint.getLowerBound(), dataType,
                                        rangeConstraint.getUpperBound(), upperDataType));
                }
            }
        }
        if (dataType == null) {
            indexConstraint.setInvalid("A Range Constraint MUST define at least a lower or an upper bound!");
        } else {
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.DATATYPE, dataType);
        }
        if (rangeConstraint.isInclusive()) {
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.LE, rangeConstraint.getUpperBound());
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.GE, rangeConstraint.getLowerBound());
        } else {
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.LT, rangeConstraint.getUpperBound());
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.GT, rangeConstraint.getLowerBound());
        }
    }

    /**
     * @param indexConstraint
     * @param textConstraint
     */
    private void initIndexConstraint(IndexConstraint indexConstraint, TextConstraint textConstraint) {
        List<IndexValue> textValues = new ArrayList<IndexValue>(textConstraint.getTexts().size());
        for(String text : textConstraint.getTexts()){
            textValues.add(indexValueFactory.createIndexValue(
                valueFactory.createText(text)));
        }
        indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.DATATYPE, IndexDataTypeEnum.TXT.getIndexType());
        indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.LANG, textConstraint.getLanguages());
        switch (textConstraint.getPatternType()) {
            case none:
                indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.EQ, textValues);
                break;
            case wildcard:
                indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.WILDCARD, textValues);
                break;
            case regex:
                indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.REGEX, textValues);
                break;
            default:
                indexConstraint.setInvalid(String.format(
                    "PatterType %s not supported for Solr Index Queries!", textConstraint.getPatternType()));
        }
    }

    /**
     * @param indexConstraint
     * @param refConstraint
     */
    private void initIndexConstraint(IndexConstraint indexConstraint, ValueConstraint valueConstraint) {
        if (valueConstraint.getValue() == null) {
            indexConstraint.setInvalid(String.format(
                "ValueConstraint without a value - that check only any value for " +
                "the parsed datatypes %s is present - can not be supported by a Solr query!",
                valueConstraint.getDataTypes()));
        } else {
            // first process the parsed dataTypes to get the supported types
            Collection<IndexDataType> indexDataTypes = new ArrayList<IndexDataType>();
            if (valueConstraint.getDataTypes() != null) {
                for (String dataType : valueConstraint.getDataTypes()) {
                    IndexDataTypeEnum indexDataTypeEnumEntry = IndexDataTypeEnum.forUri(dataType);
                    if (indexDataTypeEnumEntry != null) {
                        indexDataTypes.add(indexDataTypeEnumEntry.getIndexType());
                    } else {
                        // TODO: Add possibility to add warnings to indexConstraints
                        log.warn("A Datatype parsed for a ValueConstraint is not " +
                        		"supported and will be ignored (dataTypeUri={})",
                                dataType);
                    }
                }
            }
            IndexDataType indexDataType;
            if(indexDataTypes.isEmpty()){
                indexDataType = null;
            } else {
                Iterator<IndexDataType> it = indexDataTypes.iterator();
                indexDataType = it.next();
                if(it.hasNext()){
                    log.warn("Only a single DataType is supported for ValueConstraints" +
                    		"used: {} ignored {}", indexDataType,
                    		ModelUtils.asCollection(it));
                }
            }
            IndexValue constraintValue;
            if (indexDataType == null) { // if no supported types are present
                // get the dataType based on the type of the value
                try {
                    constraintValue = indexValueFactory.createIndexValue(valueConstraint.getValue());
                } catch (NoConverterException e) {
                    // if not found use the toString() and string as type
                    indexDataType = IndexDataTypeEnum.STR.getIndexType();
                    log.warn(String
                            .format(
                                "Unable to create IndexValue for value %s (type: %s). Create IndexValue manually by using the first parsed IndexDataType %s",
                                valueConstraint.getValue(), valueConstraint.getValue().getClass(),
                                indexDataType));
                    constraintValue = new IndexValue(valueConstraint.getValue().toString(), indexDataType);
                }
            } else { // one or more supported dataTypes are present
                constraintValue = new IndexValue(valueConstraint.getValue().toString(), indexDataType);
            }
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.DATATYPE, constraintValue);
            if(IndexDataTypeEnum.TXT.getIndexType().equals(constraintValue.getType())){
                //NOTE: in case of TEXT we need also to add the language to create a valid
                //query!
                indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.LANG, 
                    Collections.singleton(constraintValue.getLanguage()));
            }
            indexConstraint.setFieldConstraint(IndexConstraintTypeEnum.EQ, constraintValue);
        }
    }

    private SolrQuery initSolrQuery(Query entityhubQuery) {
        SolrQuery query = new SolrQuery();
        // first add a filterquery for the domain if present
        if (domain != null) {
            query.addFilterQuery(String.format("%s:%s", fieldMapper.getDocumentDomainField(),
                SolrUtil.escapeSolrSpecialChars(domain)));
        }
        // than add the offset
        query.setStart(entityhubQuery.getOffset());
        // and the limit
        if (entityhubQuery.getLimit() != null) {
            if (entityhubQuery.getLimit().compareTo(MAX_QUERY_RESULTS) <= 0) {
                query.setRows(entityhubQuery.getLimit());
            } else {
                log.warn(String.format(
                    "Parsed Number of QueryResults %d is greater than the allowed maximum of %d!",
                    entityhubQuery.getLimit(), MAX_QUERY_RESULTS));
            }
        } else {
            // maybe remove that to prevent to many results! But for now I would
            // rather like to have a default value within the FieldQuery!
            // e.g. set by the FieldQueryFactory when creating new queries!
            query.setRows(MAX_QUERY_RESULTS);
        }
        return query;
    }

    /**
     * Getter for the domain set as FilterQuery to all generated SolrQueries
     * 
     * @return the domain or <code>null</code> if no domain is set
     */
    public final String getDomain() {
        return domain;
    }

    /**
     * Setter for the domain. If an empty string is parsed, than the domain is set to <code>null</code>,
     * otherwise the parsed value is set. Parse <code>null</code> to deactivated the usage of domains
     * 
     * @param domain
     *            the domain or <code>null</code> if no domain is active
     */
    public final void setDomain(String domain) {
        if (domain != null && domain.isEmpty()) {
            this.domain = null;
        } else {
            this.domain = domain;
        }
    }

    /**
     * getter for the maximum number of results allowed
     * 
     * @return the maximum number of results that can be set
     */
    public final Integer getMaxQueryResults() {
        return maxQueryResults;
    }

    /**
     * Setter for the maximum number of results allowed. If <code>null</code> is parsed than the value is set
     * to {@link #MAX_QUERY_RESULTS}. If a value smaller than {@link #getDefaultQueryResults()} is parsed,
     * than the value is set to {@link #getDefaultQueryResults()}.
     * 
     * @param maxQueryResults
     *            The maximum number of queries allowed
     */
    public final void setMaxQueryResults(Integer maxQueryResults) {
        if (maxQueryResults == null) {
            this.maxQueryResults = MAX_QUERY_RESULTS;
        } else if (maxQueryResults.compareTo(defaultQueryResults) <= 0) {
            this.maxQueryResults = defaultQueryResults;
        } else {
            this.maxQueryResults = maxQueryResults;
        }
    }

    /**
     * Getter for the default number of query results. This is used if a parsed Query does not define the
     * number of results.
     * 
     * @return the default value for the number of query results
     */
    public final Integer getDefaultQueryResults() {
        return defaultQueryResults;
    }

    /**
     * Setter for the default number of query results. This is used if a parsed Query does not define the
     * number of results. If <code>null</code> or a value <code><= 0</code>is parsed, than the value is set to
     * the lower value of {@link #DEFAULT_QUERY_RESULTS} ({@value #DEFAULT_QUERY_RESULTS}) and
     * {@link #getMaxQueryResults()}. If a value <code>>=</code> {@link #getMaxQueryResults()} is parsed, than
     * the value is set to {@link #getMaxQueryResults()}.
     * 
     * @param defaultQueryResults
     *            the default number of results for queries
     */
    public final void setDefaultQueryResults(Integer defaultQueryResults) {
        if (defaultQueryResults == null || defaultQueryResults.intValue() <= 0) {
            this.defaultQueryResults = Math.min(DEFAULT_QUERY_RESULTS, maxQueryResults);
        } else if (defaultQueryResults.compareTo(maxQueryResults) >= 0) {
            this.defaultQueryResults = maxQueryResults;
        } else {
            this.defaultQueryResults = defaultQueryResults;
        }
    }

    /**
     * Class internally used to process FieldConstraint. This class accesses the
     * {@link SolrQueryFactory#constraintEncoders} map.
     * 
     * @author Rupert Westenthaler
     * 
     */
    private class IndexConstraint {
        private final Map<IndexConstraintTypeEnum,Object> fieldConstraints = new EnumMap<IndexConstraintTypeEnum,Object>(
                IndexConstraintTypeEnum.class);
        private List<String> invalidMessages = new ArrayList<String>();

        /**
         * Creates a Field Term for the parsed path
         * 
         * @param path
         *            the path
         * @throws IllegalArgumentException
         *             If the path is <code>null</code> empty.
         */
        public IndexConstraint(List<String> field) throws IllegalArgumentException {
            if (field == null || field.isEmpty()) {
                throw new IllegalArgumentException("The parsed path MUST NOT be NULL nor empty!");
            }
            fieldConstraints.put(IndexConstraintTypeEnum.FIELD, field);
        }

        /**
         * Set an explanatory error message to tell that this IndexConstraint cannot be used. e.g. if the
         * conversion of a {@link Constraint} to an {@link IndexConstraint} was unsuccessful.
         * 
         * @param message
         *            an message to explain why the constraint is not valid
         */
        public void setInvalid(String message) {
            this.invalidMessages.add(message);
        }

        /**
         * Returns <code>true</code> if this index constraint is invalid and can not be used for the
         * IndexQuery. If the state is <code>true</code> it indicates, that the conversion to a
         * {@link Constraint } to an {@link IndexConstraint} was not successful!
         * 
         * @return the state
         */
        public boolean isInvalid() {
            return !invalidMessages.isEmpty();
        }

        /**
         * Getter for the Messages why this index constraint is not valid
         * 
         * @return the messages. An empty List if {@link #isInvalid()} returns <code>false</code>
         */
        public List<String> getInvalidMessages() {
            return invalidMessages;
        }

        /**
         * Sets an IndexConstraintType to a specific value
         * 
         * @param constraintType
         *            the type of the constraint
         * @param value
         *            the value. <code>null</code> is permitted, but usually it is not needed to add
         *            <code>null</code> constraints, because they are automatically added if needed (e.g. a
         *            range constraint with an open lower bound)
         * @throws IllegalArgumentException
         *             if <code>null</code> is parsed as constraint type
         */
        public void setFieldConstraint(IndexConstraintTypeEnum constraintType, Object value) throws IllegalArgumentException {
            if (constraintType == null) {
                // just returning here would also be OK, but better to find errors early by
                // looking at stack traces
                throw new IllegalArgumentException("Parameter IndexConstraintTypeEnum MUST NOT be NULL");
            }
            IndexConstraintTypeEncoder<?> encoder = constraintEncoders.get(constraintType);
            if (encoder == null) {
                throw new IllegalArgumentException(String.format(
                    "No Encoder for IndexConstraintType %s present!", constraintType));
            }
            // accept null values and values that are supported by the encoder!
            if (value == null || encoder.acceptsValueType().isAssignableFrom(value.getClass())) {
                this.fieldConstraints.put(constraintType, value);
                // we need also check the dependent types!
                for (IndexConstraintTypeEnum dependent : encoder.dependsOn()) {
                    // if a dependent type is missing, add it with the default value!
                    if (!fieldConstraints.containsKey(dependent)) {
                        // if missing, set the dependent to null (default value)
                        setFieldConstraint(dependent, null);
                    }
                }
            } else {
                throw new IllegalArgumentException(
                        String.format(
                            "The Encoder %s for IndexConstraintType %s does not support values of type %s (supported Type: %s)!",
                            encoder.getClass(), constraintType, value.getClass(), encoder.acceptsValueType()));
            }
        }

        @SuppressWarnings("unchecked")
        public void encode(StringBuilder queryString) {
            if (isInvalid()) {
                throw new IllegalStateException(String.format(
                    "Unable to encode an invalid IndexConstraint (invalid messages: %s)",
                    getInvalidMessages()));
            } else {
                EncodedConstraintParts encodedConstraintParts = new EncodedConstraintParts();
                for (Entry<IndexConstraintTypeEnum,Object> constraint : fieldConstraints.entrySet()) {
                    // NOTE: type checks are already performed in the setFieldConstraint method!
                    ((IndexConstraintTypeEncoder<Object>) constraintEncoders.get(constraint.getKey()))
                            .encode(encodedConstraintParts, constraint.getValue());
                }
                // now take the parts and create the constraint!
                encodeSolrConstraint(queryString, encodedConstraintParts);
            }

        }

        private StringBuilder encodeSolrConstraint(StringBuilder queryString,
                                                   EncodedConstraintParts encodedConstraintParts) {
            // list of all constraints that need to be connected with OR
            List<List<StringBuilder>> constraints = new ArrayList<List<StringBuilder>>();
            log.info("Constriants: "+encodedConstraintParts);
            // init with a single constraint
            constraints.add(new ArrayList<StringBuilder>(Arrays.asList(new StringBuilder())));
            for (Entry<ConstraintTypePosition,Set<Set<String>>> entry : encodedConstraintParts) {
                // one position may contain multiple options that need to be connected with OR
//                log.info("process: pos {} ({})",entry.getKey().getPos(),entry.getKey());
                Set<Set<String>> orParts = entry.getValue();
                int constraintsSize = constraints.size();
                int i = 0;
                for (Set<String> andParts : orParts) {
//                    log.info("  OR {}",andParts);
                    i++;
                    // add the and constraints to all or
                    if (i == orParts.size()) {
                        // for the last iteration, append the part to the existing constraints
                        for (int j = 0; j < constraintsSize; j++) {
//                            String parsedConstraint = constraints.get(j).toString();
                            encodeAndParts(andParts, constraints.get(j));
//                            log.info("    appand AND {} to [{}]:{} -> {}",
//                                new Object[]{andParts,j,parsedConstraint, constraints.get(j).toString()});
                        }
                    } else {
                        // if there is more than one value, we need to generate new variants for
                        // every option other than the last.
                        for (int j = 0; j < constraintsSize; j++) {
                            //we need a deep "clone" of the Constraints of index 'j'
                            List<StringBuilder> additional = new ArrayList<StringBuilder>(constraints.get(j).size());
                            for(StringBuilder sb : constraints.get(j)){
                                additional.add(new StringBuilder(sb));
                            }
                            constraints.add(additional);
//                            String parsedConstraint = constraints.get(j).toString();
                            encodeAndParts(andParts, additional);
//                            log.info("    create AND {} to [{}]:{} -> {}",
//                                new Object[]{andParts,j,parsedConstraint, constraints.get(j).toString()});
                        }
                    }
                }
            }
            // now combine the different options to a single query string
            boolean firstOr = true;
            for (List<StringBuilder> constraint : constraints) {
                if (constraint.size() > 0) {
                    if (firstOr) {
                        queryString.append('(');
                        firstOr = false;
                    } else {
                        queryString.append(") OR (");
                    }
                    boolean firstAnd = true;
                    for (StringBuilder andConstraint : constraint) {
                        if (andConstraint.length() > 0) {
                            if (firstAnd) {
                                queryString.append('(');
                                firstAnd = false;
                            } else {
                                queryString.append(" AND (");
                            }
                            queryString.append(andConstraint);
                            queryString.append(')');
                        }
                    }
                } // else ignore empty constraints
            }
            queryString.append(')');
            return queryString;
        }

        /**
         * @param andParts
         * @param andConstaint
         */
        private void encodeAndParts(Set<String> andParts, List<StringBuilder> andConstaint) {
            int andConstaintSize = andConstaint.size();
            int k = 0;
            for (String part : andParts) {
                k++;
                // add the AND part of this constraint position with all parts of the others
                if (k == andParts.size()) {
                    // for the last iteration, append the part to the existing constraints
                    for (int j = 0; j < andConstaintSize; j++) {
                        andConstaint.get(j).append(part);
                    }
                } else {
                    // if there is more than one value, we need to generate new variants for
                    // every option other than the last.
                    for (int j = 0; j < andConstaintSize; j++) {
                        StringBuilder additional = new StringBuilder(andConstaint.get(j));
                        additional.append(part);
                        andConstaint.add(additional);
                    }
                }
            }
        }

        // /**
        // * NOTE: removed, because currently not needed. If re-added, this Method needs also
        // * to remove (recursively) dependent with the default value
        // * Removes the according index constraint if present
        // * @param constraintType the constraint to remove
        // * @throws IllegalArgumentException if <code>null</code> is parsed as constraint type
        // */
        // public void removeFieldConstraint(IndexConstraintTypeEnum constraintType) throws
        // IllegalArgumentException {
        // if(constraintType == null){
        // //just returning here would also be OK, but better to find errors early by
        // //looking at stack traces
        // throw new IllegalArgumentException("Parameter IndexConstraintTypeEnum MUST NOT be NULL");
        // }
        // this.fieldConstraints.remove(constraintType);
        // }
    }
    public static void main(String[] args) {
        SolrQueryFactory factory = new SolrQueryFactory(
            InMemoryValueFactory.getInstance(), 
            IndexValueFactory.getInstance(), 
            new SolrFieldMapper(null));
        FieldQuery query = DefaultQueryFactory.getInstance().createFieldQuery();
//        query.setConstraint("urn:field2", new TextConstraint("test","en","de"));
        query.setConstraint("urn:field3", new TextConstraint(Arrays.asList(
            "text value","anothertest","some more values"),"en","de",null));
        query.addSelectedField("urn:field2a");
        query.addSelectedField("urn:field3");
        query.setLimit(5);
        query.setOffset(5);
        SolrQuery solrQuery = factory.parseFieldQuery(query, SELECT.QUERY);
        System.out.println(solrQuery.getQuery());
    }
}
