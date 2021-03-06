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
package org.apache.nifi.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.WebApplicationException;

import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.connectable.Funnel;
import org.apache.nifi.connectable.Port;
import org.apache.nifi.controller.ContentAvailability;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.Counter;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.FlowFileQueue;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.ScheduledState;
import org.apache.nifi.controller.repository.ContentNotFoundException;
import org.apache.nifi.controller.repository.claim.ContentDirection;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.diagnostics.SystemDiagnostics;
import org.apache.nifi.flowfile.FlowFilePrioritizer;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.groups.ProcessGroupCounts;
import org.apache.nifi.groups.RemoteProcessGroup;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.QueueSize;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventRepository;
import org.apache.nifi.provenance.SearchableFields;
import org.apache.nifi.provenance.lineage.ComputeLineageSubmission;
import org.apache.nifi.provenance.search.Query;
import org.apache.nifi.provenance.search.QueryResult;
import org.apache.nifi.provenance.search.QuerySubmission;
import org.apache.nifi.provenance.search.SearchTerm;
import org.apache.nifi.provenance.search.SearchTerms;
import org.apache.nifi.provenance.search.SearchableField;
import org.apache.nifi.remote.RootGroupPort;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.BulletinRepository;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.apache.nifi.search.SearchContext;
import org.apache.nifi.search.SearchResult;
import org.apache.nifi.search.Searchable;
import org.apache.nifi.web.security.user.NiFiUserUtils;
import org.apache.nifi.services.FlowService;
import org.apache.nifi.user.NiFiUser;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.NiFiCoreException;
import org.apache.nifi.web.ResourceNotFoundException;
import org.apache.nifi.web.api.dto.BulletinDTO;
import org.apache.nifi.web.api.dto.DocumentedTypeDTO;
import org.apache.nifi.web.api.dto.DtoFactory;
import org.apache.nifi.web.api.dto.provenance.AttributeDTO;
import org.apache.nifi.web.api.dto.provenance.ProvenanceDTO;
import org.apache.nifi.web.api.dto.provenance.ProvenanceEventDTO;
import org.apache.nifi.web.api.dto.provenance.ProvenanceOptionsDTO;
import org.apache.nifi.web.api.dto.provenance.ProvenanceRequestDTO;
import org.apache.nifi.web.api.dto.provenance.ProvenanceResultsDTO;
import org.apache.nifi.web.api.dto.provenance.ProvenanceSearchableFieldDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageRequestDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageRequestDTO.LineageRequestType;
import org.apache.nifi.web.api.dto.search.ComponentSearchResultDTO;
import org.apache.nifi.web.api.dto.search.SearchResultsDTO;
import org.apache.nifi.web.api.dto.status.ControllerStatusDTO;
import org.apache.nifi.web.api.dto.status.ProcessGroupStatusDTO;
import org.apache.nifi.web.api.dto.status.StatusHistoryDTO;
import org.apache.nifi.web.util.DownloadableContent;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.admin.service.UserService;
import org.apache.nifi.authorization.DownloadAuthorization;
import org.apache.nifi.processor.DataUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

/**
 *
 */
public class ControllerFacade implements ControllerServiceProvider {

    private static final Logger logger = LoggerFactory.getLogger(ControllerFacade.class);

    // nifi components
    private FlowController flowController;
    private FlowService flowService;
    private UserService userService;

    // properties
    private NiFiProperties properties;
    private DtoFactory dtoFactory;

    /**
     * Creates an archive of the current flow.
     */
    public void createArchive() {
        flowService.saveFlowChanges(TimeUnit.SECONDS, 0, true);
    }

    /**
     * Returns the group id that contains the specified processor.
     *
     * @param processorId
     * @return
     */
    public String findProcessGroupIdForProcessor(String processorId) {
        final ProcessGroup rootGroup = flowController.getGroup(flowController.getRootGroupId());
        final ProcessorNode processor = rootGroup.findProcessor(processorId);
        if (processor == null) {
            return null;
        } else {
            return processor.getProcessGroup().getIdentifier();
        }
    }

    /**
     * Sets the name of this controller.
     *
     * @param name
     */
    public void setName(String name) {
        flowController.setName(name);
    }

    /**
     * Sets the comments of this controller.
     *
     * @param comments
     */
    public void setComments(String comments) {
        flowController.setComments(comments);
    }

    /**
     * Sets the max timer driven thread count of this controller.
     *
     * @param maxTimerDrivenThreadCount
     */
    public void setMaxTimerDrivenThreadCount(int maxTimerDrivenThreadCount) {
        flowController.setMaxTimerDrivenThreadCount(maxTimerDrivenThreadCount);
    }

    /**
     * Sets the max event driven thread count of this controller.
     *
     * @param maxEventDrivenThreadCount
     */
    public void setMaxEventDrivenThreadCount(int maxEventDrivenThreadCount) {
        flowController.setMaxEventDrivenThreadCount(maxEventDrivenThreadCount);
    }

    /**
     * Gets the root group id.
     *
     * @return
     */
    public String getRootGroupId() {
        return flowController.getRootGroupId();
    }

    /**
     * Gets the input ports on the root group.
     *
     * @return
     */
    public Set<RootGroupPort> getInputPorts() {
        final Set<RootGroupPort> inputPorts = new HashSet<>();
        ProcessGroup rootGroup = flowController.getGroup(flowController.getRootGroupId());
        for (final Port port : rootGroup.getInputPorts()) {
            if (port instanceof RootGroupPort) {
                inputPorts.add((RootGroupPort) port);
            }
        }
        return inputPorts;
    }

    /**
     * Gets the output ports on the root group.
     *
     * @return
     */
    public Set<RootGroupPort> getOutputPorts() {
        final Set<RootGroupPort> outputPorts = new HashSet<>();
        ProcessGroup rootGroup = flowController.getGroup(flowController.getRootGroupId());
        for (final Port port : rootGroup.getOutputPorts()) {
            if (port instanceof RootGroupPort) {
                outputPorts.add((RootGroupPort) port);
            }
        }
        return outputPorts;
    }

    /**
     * Returns the status history for the specified processor.
     *
     * @param groupId
     * @param processorId
     * @return
     */
    public StatusHistoryDTO getProcessorStatusHistory(final String groupId, final String processorId) {
        return flowController.getProcessorStatusHistory(processorId);
    }

    /**
     * Returns the status history for the specified connection.
     *
     * @param groupId
     * @param connectionId
     * @return
     */
    public StatusHistoryDTO getConnectionStatusHistory(final String groupId, final String connectionId) {
        return flowController.getConnectionStatusHistory(connectionId);
    }

    /**
     * Returns the status history for the specified process group.
     *
     * @param groupId
     * @return
     */
    public StatusHistoryDTO getProcessGroupStatusHistory(final String groupId) {
        return flowController.getProcessGroupStatusHistory(groupId);
    }

    /**
     * Returns the status history for the specified remote process group.
     *
     * @param groupId
     * @param remoteProcessGroupId
     * @return
     */
    public StatusHistoryDTO getRemoteProcessGroupStatusHistory(final String groupId, final String remoteProcessGroupId) {
        return flowController.getRemoteProcessGroupStatusHistory(remoteProcessGroupId);
    }

    /**
     * Get the node id of this controller.
     *
     * @return
     */
    public NodeIdentifier getNodeId() {
        return flowController.getNodeId();
    }

    public boolean isClustered() {
        return flowController.isClustered();
    }

    /**
     * Gets the name of this controller.
     *
     * @return
     */
    public String getName() {
        return flowController.getName();
    }

    public String getInstanceId() {
        return flowController.getInstanceId();
    }

    /**
     * Gets the comments of this controller.
     *
     * @return
     */
    public String getComments() {
        return flowController.getComments();
    }

    /**
     * Gets the max timer driven thread count of this controller.
     *
     * @return
     */
    public int getMaxTimerDrivenThreadCount() {
        return flowController.getMaxTimerDrivenThreadCount();
    }

    /**
     * Gets the max event driven thread count of this controller.
     *
     * @return
     */
    public int getMaxEventDrivenThreadCount() {
        return flowController.getMaxEventDrivenThreadCount();
    }

    /**
     * Gets the FlowFileProcessor types that this controller supports.
     *
     * @return
     */
    public Set<DocumentedTypeDTO> getFlowFileProcessorTypes() {
        return dtoFactory.fromDocumentedTypes(ExtensionManager.getExtensions(Processor.class));
    }

    /**
     * Gets the FlowFileComparator types that this controller supports.
     *
     * @return
     */
    public Set<DocumentedTypeDTO> getFlowFileComparatorTypes() {
        return dtoFactory.fromDocumentedTypes(ExtensionManager.getExtensions(FlowFilePrioritizer.class));
    }

    /**
     * Gets the counters for this controller.
     *
     * @return
     */
    public List<Counter> getCounters() {
        return flowController.getCounters();
    }

    /**
     * Resets the counter with the specified id.
     * @param id
     * @return 
     */
    public Counter resetCounter(final String id) {
        final Counter counter = flowController.resetCounter(id);

        if (counter == null) {
            throw new ResourceNotFoundException(String.format("Unable to find Counter with id '%s'.", id));
        }

        return counter;
    }

    /**
     * Return the controller service for the specified identifier.
     *
     * @param serviceIdentifier
     * @return
     */
    @Override
    public ControllerService getControllerService(String serviceIdentifier) {
        return flowController.getControllerService(serviceIdentifier);
    }

    @Override
    public ControllerServiceNode createControllerService(String type, String id, Map<String, String> properties) {
        return flowController.createControllerService(type, id, properties);
    }

    @Override
    public Set<String> getControllerServiceIdentifiers(Class<? extends ControllerService> serviceType) {
        return flowController.getControllerServiceIdentifiers(serviceType);
    }

    @Override
    public ControllerServiceNode getControllerServiceNode(final String id) {
        return flowController.getControllerServiceNode(id);
    }

    @Override
    public boolean isControllerServiceEnabled(final ControllerService service) {
        return flowController.isControllerServiceEnabled(service);
    }

    @Override
    public boolean isControllerServiceEnabled(final String serviceIdentifier) {
        return flowController.isControllerServiceEnabled(serviceIdentifier);
    }

    /**
     * Gets the status of this controller.
     *
     * @return
     */
    public ControllerStatusDTO getControllerStatus() {
        final ProcessGroup rootGroup = flowController.getGroup(flowController.getRootGroupId());

        final QueueSize controllerQueueSize = flowController.getTotalFlowFileCount(rootGroup);
        final ControllerStatusDTO controllerStatus = new ControllerStatusDTO();
        controllerStatus.setActiveThreadCount(flowController.getActiveThreadCount());
        controllerStatus.setQueued(FormatUtils.formatCount(controllerQueueSize.getObjectCount()) + " / " + FormatUtils.formatDataSize(controllerQueueSize.getByteCount()));

        final BulletinRepository bulletinRepository = getBulletinRepository();
        final List<Bulletin> results = bulletinRepository.findBulletinsForController();
        final List<BulletinDTO> bulletinDtos = new ArrayList<>(results.size());
        for (final Bulletin bulletin : results) {
            bulletinDtos.add(dtoFactory.createBulletinDto(bulletin));
        }
        controllerStatus.setBulletins(bulletinDtos);

        final ProcessGroupCounts counts = rootGroup.getCounts();
        controllerStatus.setRunningCount(counts.getRunningCount());
        controllerStatus.setStoppedCount(counts.getStoppedCount());
        controllerStatus.setInvalidCount(counts.getInvalidCount());
        controllerStatus.setDisabledCount(counts.getDisabledCount());
        controllerStatus.setActiveRemotePortCount(counts.getActiveRemotePortCount());
        controllerStatus.setInactiveRemotePortCount(counts.getInactiveRemotePortCount());

        return controllerStatus;
    }

    /**
     * Gets the status for the specified process group.
     *
     * @param groupId
     * @return
     */
    public ProcessGroupStatusDTO getProcessGroupStatus(final String groupId) {
        final ProcessGroupStatus processGroupStatus = flowController.getGroupStatus(groupId);
        if (processGroupStatus == null) {
            throw new ResourceNotFoundException(String.format("Unable to locate group with id '%s'.", groupId));
        }
        return dtoFactory.createProcessGroupStatusDto(flowController.getBulletinRepository(), processGroupStatus);
    }

    /**
     * Gets the BulletinRepository.
     *
     * @return
     */
    public BulletinRepository getBulletinRepository() {
        return flowController.getBulletinRepository();
    }

    /**
     * Saves the state of the flow controller.
     *
     * @throws NiFiCoreException
     */
    public void save() throws NiFiCoreException {
        // save the flow controller
        final long writeDelaySeconds = FormatUtils.getTimeDuration(properties.getFlowServiceWriteDelay(), TimeUnit.SECONDS);
        flowService.saveFlowChanges(TimeUnit.SECONDS, writeDelaySeconds);
    }

    /**
     * Returns the socket port that the Cluster Manager is listening on for
     * Site-to-Site communications
     *
     * @return
     */
    public Integer getClusterManagerRemoteSiteListeningPort() {
        return flowController.getClusterManagerRemoteSiteListeningPort();
    }

    /**
     * Indicates whether or not Site-to-Site communications with the Cluster
     * Manager are secure
     *
     * @return
     */
    public Boolean isClusterManagerRemoteSiteCommsSecure() {
        return flowController.isClusterManagerRemoteSiteCommsSecure();
    }

    /**
     * Returns the socket port that the local instance is listening on for
     * Site-to-Site communications
     *
     * @return
     */
    public Integer getRemoteSiteListeningPort() {
        return flowController.getRemoteSiteListeningPort();
    }

    /**
     * Indicates whether or not Site-to-Site communications with the local
     * instance are secure
     *
     * @return
     */
    public Boolean isRemoteSiteCommsSecure() {
        return flowController.isRemoteSiteCommsSecure();
    }

    /**
     * Returns a SystemDiagnostics that describes the current state of the node
     *
     * @return
     */
    public SystemDiagnostics getSystemDiagnostics() {
        return flowController.getSystemDiagnostics();
    }

    /**
     * Gets the available options for searching provenance.
     *
     * @return
     */
    public ProvenanceOptionsDTO getProvenanceSearchOptions() {
        final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();

        // create the search options dto
        final ProvenanceOptionsDTO searchOptions = new ProvenanceOptionsDTO();
        final List<ProvenanceSearchableFieldDTO> searchableFieldNames = new ArrayList<>();
        final List<SearchableField> fields = provenanceRepository.getSearchableFields();
        for (final SearchableField field : fields) {
            final ProvenanceSearchableFieldDTO searchableField = new ProvenanceSearchableFieldDTO();
            searchableField.setId(field.getIdentifier());
            searchableField.setField(field.getSearchableFieldName());
            searchableField.setLabel(field.getFriendlyName());
            searchableField.setType(field.getFieldType().name());
            searchableFieldNames.add(searchableField);
        }
        final List<SearchableField> searchableAttributes = provenanceRepository.getSearchableAttributes();
        for (final SearchableField searchableAttr : searchableAttributes) {
            final ProvenanceSearchableFieldDTO searchableAttribute = new ProvenanceSearchableFieldDTO();
            searchableAttribute.setId(searchableAttr.getIdentifier());
            searchableAttribute.setField(searchableAttr.getSearchableFieldName());
            searchableAttribute.setLabel(searchableAttr.getFriendlyName());
            searchableAttribute.setType(searchableAttr.getFieldType().name());
            searchableFieldNames.add(searchableAttribute);
        }
        searchOptions.setSearchableFields(searchableFieldNames);
        return searchOptions;
    }

    /**
     * Submits a provenance query.
     *
     * @param provenanceDto
     * @return
     */
    public ProvenanceDTO submitProvenance(ProvenanceDTO provenanceDto) {
        final ProvenanceRequestDTO requestDto = provenanceDto.getRequest();

        // create the query
        final Query query = new Query(provenanceDto.getId());

        // if the request was specified
        if (requestDto != null) {
            // add each search term specified
            final Map<String, String> searchTerms = requestDto.getSearchTerms();
            if (searchTerms != null) {
                for (final Map.Entry<String, String> searchTerm : searchTerms.entrySet()) {
                    SearchableField field;

                    field = SearchableFields.getSearchableField(searchTerm.getKey());
                    if (field == null) {
                        field = SearchableFields.newSearchableAttribute(searchTerm.getKey());
                    }
                    query.addSearchTerm(SearchTerms.newSearchTerm(field, searchTerm.getValue()));
                }
            }

            // specify the start date if specified
            if (requestDto.getStartDate() != null) {
                query.setStartDate(requestDto.getStartDate());
            }

            // ensure an end date is populated
            if (requestDto.getEndDate() != null) {
                query.setEndDate(requestDto.getEndDate());
            }

            // set the min/max file size
            query.setMinFileSize(requestDto.getMinimumFileSize());
            query.setMaxFileSize(requestDto.getMaximumFileSize());

            // set the max results desired
            query.setMaxResults(requestDto.getMaxResults());
        }

        // submit the query to the provenance repository
        final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();
        final QuerySubmission querySubmission = provenanceRepository.submitQuery(query);

        // return the query with the results populated at this point
        return getProvenanceQuery(querySubmission.getQueryIdentifier());
    }

    /**
     * Retrieves the results of a provenance query.
     *
     * @param provenanceId
     * @return
     */
    public ProvenanceDTO getProvenanceQuery(String provenanceId) {
        try {
            // get the query to the provenance repository
            final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();
            final QuerySubmission querySubmission = provenanceRepository.retrieveQuerySubmission(provenanceId);

            // ensure the query results could be found
            if (querySubmission == null) {
                throw new ResourceNotFoundException("Cannot find the results for the specified provenance requests. Results may have been purged.");
            }

            // get the original query and the results
            final Query query = querySubmission.getQuery();
            final QueryResult queryResult = querySubmission.getResult();

            // build the response
            final ProvenanceDTO provenanceDto = new ProvenanceDTO();
            final ProvenanceRequestDTO requestDto = new ProvenanceRequestDTO();
            final ProvenanceResultsDTO resultsDto = new ProvenanceResultsDTO();

            // include the original request and results
            provenanceDto.setRequest(requestDto);
            provenanceDto.setResults(resultsDto);

            // convert the original request
            requestDto.setStartDate(query.getStartDate());
            requestDto.setEndDate(query.getEndDate());
            requestDto.setMinimumFileSize(query.getMinFileSize());
            requestDto.setMaximumFileSize(query.getMaxFileSize());
            requestDto.setMaxResults(query.getMaxResults());
            if (query.getSearchTerms() != null) {
                final Map<String, String> searchTerms = new HashMap<>();
                for (final SearchTerm searchTerm : query.getSearchTerms()) {
                    searchTerms.put(searchTerm.getSearchableField().getFriendlyName(), searchTerm.getValue());
                }
                requestDto.setSearchTerms(searchTerms);
            }

            // convert the provenance
            provenanceDto.setId(query.getIdentifier());
            provenanceDto.setSubmissionTime(querySubmission.getSubmissionTime());
            provenanceDto.setExpiration(queryResult.getExpiration());
            provenanceDto.setFinished(queryResult.isFinished());
            provenanceDto.setPercentCompleted(queryResult.getPercentComplete());

            // convert each event
            final List<ProvenanceEventDTO> events = new ArrayList<>();
            for (final ProvenanceEventRecord record : queryResult.getMatchingEvents()) {
                events.add(createProvenanceEventDto(record));
            }
            resultsDto.setProvenanceEvents(events);
            resultsDto.setTotalCount(queryResult.getTotalHitCount());
            resultsDto.setTotal(FormatUtils.formatCount(queryResult.getTotalHitCount()));

            // include any errors
            if (queryResult.getError() != null) {
                final Set<String> errors = new HashSet<>();
                errors.add(queryResult.getError());
                resultsDto.setErrors(errors);
            }

            // set the generated timestamp
            final Date now = new Date();
            resultsDto.setGenerated(now);
            resultsDto.setTimeOffset(TimeZone.getDefault().getOffset(now.getTime()));

            // get the oldest available event time
            final List<ProvenanceEventRecord> firstEvent = provenanceRepository.getEvents(0, 1);
            if (!firstEvent.isEmpty()) {
                resultsDto.setOldestEvent(new Date(firstEvent.get(0).getEventTime()));
            }

            provenanceDto.setResults(resultsDto);
            return provenanceDto;
        } catch (final IOException ioe) {
            throw new NiFiCoreException("An error occured while searching the provenance events.", ioe);
        }
    }

    /**
     * Submits the specified lineage request.
     *
     * @param lineageDto
     * @return
     */
    public LineageDTO submitLineage(LineageDTO lineageDto) {
        final LineageRequestDTO requestDto = lineageDto.getRequest();

        // get the provenance repo
        final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();
        final ComputeLineageSubmission result;

        // submit the event
        if (LineageRequestType.FLOWFILE.equals(requestDto.getLineageRequestType())) {
            // submit uuid
            result = provenanceRepository.submitLineageComputation(requestDto.getUuid());
        } else {
            // submit event... (parents or children)
            if (LineageRequestType.PARENTS.equals(requestDto.getLineageRequestType())) {
                result = provenanceRepository.submitExpandParents(requestDto.getEventId());
            } else {
                result = provenanceRepository.submitExpandChildren(requestDto.getEventId());
            }
        }

        return getLineage(result.getLineageIdentifier());
    }

    /**
     * Gets the lineage with the specified id.
     *
     * @param lineageId
     * @return
     */
    public LineageDTO getLineage(final String lineageId) {
        // get the query to the provenance repository
        final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();
        final ComputeLineageSubmission computeLineageSubmission = provenanceRepository.retrieveLineageSubmission(lineageId);

        // ensure the submission was found
        if (computeLineageSubmission == null) {
            throw new ResourceNotFoundException("Cannot find the results for the specified lineage request. Results may have been purged.");
        }

        return dtoFactory.createLineageDto(computeLineageSubmission);
    }

    /**
     * Deletes the query with the specified id.
     *
     * @param provenanceId
     */
    public void deleteProvenanceQuery(final String provenanceId) {
        // get the query to the provenance repository
        final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();
        final QuerySubmission querySubmission = provenanceRepository.retrieveQuerySubmission(provenanceId);
        if (querySubmission != null) {
            querySubmission.cancel();
        }
    }

    /**
     * Deletes the lineage with the specified id.
     *
     * @param lineageId
     */
    public void deleteLineage(final String lineageId) {
        // get the query to the provenance repository
        final ProvenanceEventRepository provenanceRepository = flowController.getProvenanceRepository();
        final ComputeLineageSubmission computeLineageSubmission = provenanceRepository.retrieveLineageSubmission(lineageId);
        if (computeLineageSubmission != null) {
            computeLineageSubmission.cancel();
        }
    }

    /**
     * Gets the content for the specified claim.
     *
     * @param eventId
     * @param uri
     * @param contentDirection
     * @return
     */
    public DownloadableContent getContent(final Long eventId, final String uri, final ContentDirection contentDirection) {
        try {
            final NiFiUser user = NiFiUserUtils.getNiFiUser();
            if (user == null) {
                throw new WebApplicationException(new Throwable("Unable to access details for current user."));
            }

            // get the event in order to get the filename
            final ProvenanceEventRecord event = flowController.getProvenanceRepository().getEvent(eventId);
            if (event == null) {
                throw new ResourceNotFoundException("Unable to find the specified event.");
            }

            // get the flowfile attributes
            final Map<String, String> attributes = event.getAttributes();

            // calculate the dn chain
            final List<String> dnChain = new ArrayList<>();

            // build the dn chain
            NiFiUser chainedUser = user;
            do {
                // add the entry for this user
                dnChain.add(chainedUser.getDn());

                // go to the next user in the chain
                chainedUser = chainedUser.getChain();
            } while (chainedUser != null);

            // ensure the users in this chain are allowed to download this content
            final DownloadAuthorization downloadAuthorization = userService.authorizeDownload(dnChain, attributes);
            if (!downloadAuthorization.isApproved()) {
                throw new AccessDeniedException(downloadAuthorization.getExplanation());
            }
            
            // get the filename and fall back to the idnetifier (should never happen)
            String filename = event.getAttributes().get(CoreAttributes.FILENAME.key());
            if (filename == null) {
                filename = event.getFlowFileUuid();
            }

            // get the mime-type
            final String type = event.getAttributes().get(CoreAttributes.MIME_TYPE.key());

            // get the content
            final InputStream content = flowController.getContent(event, contentDirection, user.getDn(), uri);
            return new DownloadableContent(filename, type, content);
        } catch (final ContentNotFoundException cnfe) {
            throw new ResourceNotFoundException("Unable to find the specified content.");
        } catch (final IOException ioe) {
            logger.error(String.format("Unable to get the content for event (%s) at this time.", eventId), ioe);
            throw new IllegalStateException("Unable to get the content at this time.");
        }
    }

    /**
     * Submits a replay request for the specified event id.
     *
     * @param eventId
     * @return
     */
    public ProvenanceEventDTO submitReplay(final Long eventId) {
        try {
            final NiFiUser user = NiFiUserUtils.getNiFiUser();
            if (user == null) {
                throw new WebApplicationException(new Throwable("Unable to access details for current user."));
            }

            // lookup the original event
            final ProvenanceEventRecord originalEvent = flowController.getProvenanceRepository().getEvent(eventId);
            if (originalEvent == null) {
                throw new ResourceNotFoundException("Unable to find the specified event.");
            }

            // replay the flow file
            final ProvenanceEventRecord event = flowController.replayFlowFile(originalEvent, user.getDn());

            // convert the event record
            return createProvenanceEventDto(event);
        } catch (final IOException ioe) {
            throw new NiFiCoreException("An error occured while getting the specified event.", ioe);
        }
    }

    /**
     * Get the provenance event with the specified event id.
     *
     * @param eventId
     * @return
     */
    public ProvenanceEventDTO getProvenanceEvent(final Long eventId) {
        try {
            final ProvenanceEventRecord event = flowController.getProvenanceRepository().getEvent(eventId);
            if (event == null) {
                throw new ResourceNotFoundException("Unable to find the specified event.");
            }

            // convert the event
            return createProvenanceEventDto(event);
        } catch (final IOException ioe) {
            throw new NiFiCoreException("An error occured while getting the specified event.", ioe);
        }
    }

    /**
     * Creates a ProvenanceEventDTO for the specified ProvenanceEventRecord.
     *
     * @param event
     * @return
     */
    private ProvenanceEventDTO createProvenanceEventDto(final ProvenanceEventRecord event) {
        // convert the attributes
        final Comparator<AttributeDTO> attributeComparator = new Comparator<AttributeDTO>() {
            @Override
            public int compare(AttributeDTO a1, AttributeDTO a2) {
                return Collator.getInstance(Locale.US).compare(a1.getName(), a2.getName());
            }
        };

        final SortedSet<AttributeDTO> attributes = new TreeSet<>(attributeComparator);

        final Map<String, String> updatedAttrs = event.getUpdatedAttributes();
        final Map<String, String> previousAttrs = event.getPreviousAttributes();

        // add previous attributes that haven't been modified.
        for (final Map.Entry<String, String> entry : previousAttrs.entrySet()) {
            // don't add any attributes that have been updated; we will do that next
            if (updatedAttrs.containsKey(entry.getKey())) {
                continue;
            }

            final AttributeDTO attribute = new AttributeDTO();
            attribute.setName(entry.getKey());
            attribute.setValue(entry.getValue());
            attribute.setPreviousValue(entry.getValue());
            attributes.add(attribute);
        }

        // Add all of the update attributes
        for (final Map.Entry<String, String> entry : updatedAttrs.entrySet()) {
            final AttributeDTO attribute = new AttributeDTO();
            attribute.setName(entry.getKey());
            attribute.setValue(entry.getValue());
            attribute.setPreviousValue(previousAttrs.get(entry.getKey()));
            attributes.add(attribute);
        }

        // build the event dto
        final ProvenanceEventDTO dto = new ProvenanceEventDTO();
        dto.setId(String.valueOf(event.getEventId()));
        dto.setAlternateIdentifierUri(event.getAlternateIdentifierUri());
        dto.setAttributes(attributes);
        dto.setTransitUri(event.getTransitUri());
        dto.setEventId(event.getEventId());
        dto.setEventTime(new Date(event.getEventTime()));
        dto.setEventType(event.getEventType().name());
        dto.setFileSize(FormatUtils.formatDataSize(event.getFileSize()));
        dto.setFileSizeBytes(event.getFileSize());
        dto.setComponentId(event.getComponentId());
        dto.setComponentType(event.getComponentType());
        dto.setSourceSystemFlowFileId(event.getSourceSystemFlowFileIdentifier());
        dto.setFlowFileUuid(event.getFlowFileUuid());
        dto.setRelationship(event.getRelationship());
        dto.setDetails(event.getDetails());

        final ContentAvailability contentAvailability = flowController.getContentAvailability(event);

        // content
        dto.setContentEqual(contentAvailability.isContentSame());
        dto.setInputContentAvailable(contentAvailability.isInputAvailable());
        dto.setInputContentClaimSection(event.getPreviousContentClaimSection());
        dto.setInputContentClaimContainer(event.getPreviousContentClaimContainer());
        dto.setInputContentClaimIdentifier(event.getPreviousContentClaimIdentifier());
        dto.setInputContentClaimOffset(event.getPreviousContentClaimOffset());
        dto.setInputContentClaimFileSizeBytes(event.getPreviousFileSize());
        dto.setOutputContentAvailable(contentAvailability.isOutputAvailable());
        dto.setOutputContentClaimSection(event.getContentClaimSection());
        dto.setOutputContentClaimContainer(event.getContentClaimContainer());
        dto.setOutputContentClaimIdentifier(event.getContentClaimIdentifier());
        dto.setOutputContentClaimOffset(event.getContentClaimOffset());
        dto.setOutputContentClaimFileSize(FormatUtils.formatDataSize(event.getFileSize()));
        dto.setOutputContentClaimFileSizeBytes(event.getFileSize());

        // format the previous file sizes if possible
        if (event.getPreviousFileSize() != null) {
            dto.setInputContentClaimFileSize(FormatUtils.formatDataSize(event.getPreviousFileSize()));
        }

        // replay
        dto.setReplayAvailable(contentAvailability.isReplayable());
        dto.setReplayExplanation(contentAvailability.getReasonNotReplayable());
        dto.setSourceConnectionIdentifier(event.getSourceQueueIdentifier());

        // sets the component details if it can find the component still in the flow
        setComponentDetails(dto);

        // event duration
        if (event.getEventDuration() >= 0) {
            dto.setEventDuration(event.getEventDuration());
        }

        // lineage duration
        if (event.getLineageStartDate() > 0) {
            final long lineageDuration = event.getEventTime() - event.getLineageStartDate();
            dto.setLineageDuration(lineageDuration);
        }

        // parent uuids
        final List<String> parentUuids = new ArrayList<>(event.getParentUuids());
        Collections.sort(parentUuids, Collator.getInstance(Locale.US));
        dto.setParentUuids(parentUuids);

        // child uuids
        final List<String> childUuids = new ArrayList<>(event.getChildUuids());
        Collections.sort(childUuids, Collator.getInstance(Locale.US));
        dto.setChildUuids(childUuids);

        return dto;
    }

    /**
     * Gets the name for the component with the specified id.
     *
     * @param dto
     * @return
     */
    private void setComponentDetails(final ProvenanceEventDTO dto) {
        final ProcessGroup root = flowController.getGroup(flowController.getRootGroupId());

        final Connectable connectable = root.findConnectable(dto.getComponentId());
        if (connectable != null) {
            dto.setGroupId(connectable.getProcessGroup().getIdentifier());
            dto.setComponentName(connectable.getName());
        }
    }

    /**
     * Searches this controller for the specified term.
     *
     * @param search
     * @return
     */
    public SearchResultsDTO search(final String search) {
        final ProcessGroup rootGroup = flowController.getGroup(flowController.getRootGroupId());

        final SearchResultsDTO results = new SearchResultsDTO();
        search(results, search, rootGroup);

        return results;
    }

    private void search(final SearchResultsDTO results, final String search, final ProcessGroup group) {
        final ComponentSearchResultDTO groupMatch = search(search, group);
        if (groupMatch != null) {
            results.getProcessGroupResults().add(groupMatch);
        }

        for (final ProcessorNode procNode : group.getProcessors()) {
            final ComponentSearchResultDTO match = search(search, procNode);
            if (match != null) {
                match.setGroupId(group.getIdentifier());
                results.getProcessorResults().add(match);
            }
        }

        for (final Connection connection : group.getConnections()) {
            final ComponentSearchResultDTO match = search(search, connection);
            if (match != null) {
                match.setGroupId(group.getIdentifier());
                results.getConnectionResults().add(match);
            }
        }

        for (final RemoteProcessGroup remoteGroup : group.getRemoteProcessGroups()) {
            final ComponentSearchResultDTO match = search(search, remoteGroup);
            if (match != null) {
                match.setGroupId(group.getIdentifier());
                results.getRemoteProcessGroupResults().add(match);
            }
        }

        for (final Port port : group.getInputPorts()) {
            final ComponentSearchResultDTO match = search(search, port);
            if (match != null) {
                match.setGroupId(group.getIdentifier());
                results.getInputPortResults().add(match);
            }
        }

        for (final Port port : group.getOutputPorts()) {
            final ComponentSearchResultDTO match = search(search, port);
            if (match != null) {
                match.setGroupId(group.getIdentifier());
                results.getOutputPortResults().add(match);
            }
        }

        for (final Funnel funnel : group.getFunnels()) {
            final ComponentSearchResultDTO match = search(search, funnel);
            if (match != null) {
                match.setGroupId(group.getIdentifier());
                results.getFunnelResults().add(match);
            }
        }

        for (final ProcessGroup processGroup : group.getProcessGroups()) {
            search(results, search, processGroup);
        }
    }

    private ComponentSearchResultDTO search(final String searchStr, final Port port) {
        final List<String> matches = new ArrayList<>();

        addIfAppropriate(searchStr, port.getIdentifier(), "Id", matches);
        addIfAppropriate(searchStr, port.getName(), "Name", matches);
        addIfAppropriate(searchStr, port.getComments(), "Comments", matches);

        // consider scheduled state
        if (ScheduledState.DISABLED.equals(port.getScheduledState())) {
            if (StringUtils.containsIgnoreCase("disabled", searchStr)) {
                matches.add("Run status: Disabled");
            }
        } else {
            if (StringUtils.containsIgnoreCase("invalid", searchStr) && !port.isValid()) {
                matches.add("Run status: Invalid");
            } else if (ScheduledState.RUNNING.equals(port.getScheduledState()) && StringUtils.containsIgnoreCase("running", searchStr)) {
                matches.add("Run status: Running");
            } else if (ScheduledState.STOPPED.equals(port.getScheduledState()) && StringUtils.containsIgnoreCase("stopped", searchStr)) {
                matches.add("Run status: Stopped");
            }
        }

        if (port instanceof RootGroupPort) {
            final RootGroupPort rootGroupPort = (RootGroupPort) port;

            // user access controls
            for (final String userAccessControl : rootGroupPort.getUserAccessControl()) {
                addIfAppropriate(searchStr, userAccessControl, "User access control", matches);
            }

            // group access controls
            for (final String groupAccessControl : rootGroupPort.getGroupAccessControl()) {
                addIfAppropriate(searchStr, groupAccessControl, "Group access control", matches);
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        final ComponentSearchResultDTO dto = new ComponentSearchResultDTO();
        dto.setId(port.getIdentifier());
        dto.setName(port.getName());
        dto.setMatches(matches);
        return dto;
    }

    private ComponentSearchResultDTO search(final String searchStr, final ProcessorNode procNode) {
        final List<String> matches = new ArrayList<>();
        final Processor processor = procNode.getProcessor();

        addIfAppropriate(searchStr, procNode.getIdentifier(), "Id", matches);
        addIfAppropriate(searchStr, procNode.getName(), "Name", matches);
        addIfAppropriate(searchStr, procNode.getComments(), "Comments", matches);

        // consider scheduling strategy
        if (SchedulingStrategy.EVENT_DRIVEN.equals(procNode.getSchedulingStrategy()) && StringUtils.containsIgnoreCase("event", searchStr)) {
            matches.add("Scheduling strategy: Event driven");
        } else if (SchedulingStrategy.TIMER_DRIVEN.equals(procNode.getSchedulingStrategy()) && StringUtils.containsIgnoreCase("timer", searchStr)) {
            matches.add("Scheduling strategy: Timer driven");
        } else if (SchedulingStrategy.PRIMARY_NODE_ONLY.equals(procNode.getSchedulingStrategy()) && StringUtils.containsIgnoreCase("primary", searchStr)) {
            matches.add("Scheduling strategy: On primary node");
        }

        // consider scheduled state
        if (ScheduledState.DISABLED.equals(procNode.getScheduledState())) {
            if (StringUtils.containsIgnoreCase("disabled", searchStr)) {
                matches.add("Run status: Disabled");
            }
        } else {
            if (StringUtils.containsIgnoreCase("invalid", searchStr) && !procNode.isValid()) {
                matches.add("Run status: Invalid");
            } else if (ScheduledState.RUNNING.equals(procNode.getScheduledState()) && StringUtils.containsIgnoreCase("running", searchStr)) {
                matches.add("Run status: Running");
            } else if (ScheduledState.STOPPED.equals(procNode.getScheduledState()) && StringUtils.containsIgnoreCase("stopped", searchStr)) {
                matches.add("Run status: Stopped");
            }
        }

        for (final Relationship relationship : procNode.getRelationships()) {
            addIfAppropriate(searchStr, relationship.getName(), "Relationship", matches);
        }
        addIfAppropriate(searchStr, processor.getClass().getSimpleName(), "Type", matches);

        for (final Map.Entry<PropertyDescriptor, String> entry : procNode.getProperties().entrySet()) {
            final PropertyDescriptor descriptor = entry.getKey();
            final String value = entry.getValue();
            if (StringUtils.containsIgnoreCase(value, searchStr)) {
                matches.add("Property: " + descriptor.getName() + " - " + value);
            }

            addIfAppropriate(searchStr, descriptor.getName(), "Property", matches);
            addIfAppropriate(searchStr, descriptor.getDescription(), "Property", matches);
        }

        // consider searching the processor directly
        if (processor instanceof Searchable) {
            final Searchable searchable = (Searchable) processor;

            // prepare the search context
            final SearchContext context = new StandardSearchContext(searchStr, procNode, flowController);

            // search the processor using the appropriate thread context classloader
            try (final NarCloseable x = NarCloseable.withNarLoader()) {
                final Collection<SearchResult> searchResults = searchable.search(context);
                if (CollectionUtils.isNotEmpty(searchResults)) {
                    for (final SearchResult searchResult : searchResults) {
                        matches.add(searchResult.getLabel() + ": " + searchResult.getMatch());
                    }
                }
            } catch (final Throwable t) {
                // log this as error
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        final ComponentSearchResultDTO result = new ComponentSearchResultDTO();
        result.setId(procNode.getIdentifier());
        result.setMatches(matches);
        result.setName(procNode.getName());
        return result;
    }

    private ComponentSearchResultDTO search(final String searchStr, final ProcessGroup group) {
        final List<String> matches = new ArrayList<>();
        final ProcessGroup parent = group.getParent();
        if (parent == null) {
            return null;
        }

        addIfAppropriate(searchStr, group.getIdentifier(), "Id", matches);
        addIfAppropriate(searchStr, group.getName(), "Name", matches);
        addIfAppropriate(searchStr, group.getComments(), "Comments", matches);

        if (matches.isEmpty()) {
            return null;
        }

        final ComponentSearchResultDTO result = new ComponentSearchResultDTO();
        result.setId(group.getIdentifier());
        result.setName(group.getName());
        result.setGroupId(parent.getIdentifier());
        result.setMatches(matches);
        return result;
    }

    private ComponentSearchResultDTO search(final String searchStr, final Connection connection) {
        final List<String> matches = new ArrayList<>();

        // search id and name
        addIfAppropriate(searchStr, connection.getIdentifier(), "Id", matches);
        addIfAppropriate(searchStr, connection.getName(), "Name", matches);

        // search relationships
        for (final Relationship relationship : connection.getRelationships()) {
            addIfAppropriate(searchStr, relationship.getName(), "Relationship", matches);
        }

        // search prioritizers
        final FlowFileQueue queue = connection.getFlowFileQueue();
        for (final FlowFilePrioritizer comparator : queue.getPriorities()) {
            addIfAppropriate(searchStr, comparator.getClass().getName(), "Prioritizer", matches);
        }
        
        // search expiration
        if (StringUtils.containsIgnoreCase("expires", searchStr) || StringUtils.containsIgnoreCase("expiration", searchStr)) {
            final int expirationMillis = connection.getFlowFileQueue().getFlowFileExpiration(TimeUnit.MILLISECONDS);
            if (expirationMillis > 0) {
                matches.add("FlowFile expiration: " + connection.getFlowFileQueue().getFlowFileExpiration());
            }
        }
        
        // search back pressure
        if (StringUtils.containsIgnoreCase("back pressure", searchStr) || StringUtils.containsIgnoreCase("pressure", searchStr)) {
            final String backPressureDataSize = connection.getFlowFileQueue().getBackPressureDataSizeThreshold();
            final Double backPressureBytes = DataUnit.parseDataSize(backPressureDataSize, DataUnit.B);
            if (backPressureBytes > 0) {
                matches.add("Back pressure data size: " + backPressureDataSize);
            }

            final long backPressureCount = connection.getFlowFileQueue().getBackPressureObjectThreshold();
            if (backPressureCount > 0) {
                matches.add("Back pressure count: " + backPressureCount);
            }
        }

        // search the source
        final Connectable source = connection.getSource();
        addIfAppropriate(searchStr, source.getIdentifier(), "Source id", matches);
        addIfAppropriate(searchStr, source.getName(), "Source name", matches);
        addIfAppropriate(searchStr, source.getComments(), "Source comments", matches);

        // search the destination
        final Connectable destination = connection.getDestination();
        addIfAppropriate(searchStr, destination.getIdentifier(), "Destination id", matches);
        addIfAppropriate(searchStr, destination.getName(), "Destination name", matches);
        addIfAppropriate(searchStr, destination.getComments(), "Destination comments", matches);

        if (matches.isEmpty()) {
            return null;
        }

        final ComponentSearchResultDTO result = new ComponentSearchResultDTO();
        result.setId(connection.getIdentifier());

        // determine the name of the search match
        if (StringUtils.isNotBlank(connection.getName())) {
            result.setName(connection.getName());
        } else if (!connection.getRelationships().isEmpty()) {
            final List<String> relationships = new ArrayList<>(connection.getRelationships().size());
            for (final Relationship relationship : connection.getRelationships()) {
                if (StringUtils.isNotBlank(relationship.getName())) {
                    relationships.add(relationship.getName());
                }
            }
            if (!relationships.isEmpty()) {
                result.setName(StringUtils.join(relationships, ", "));
            }
        }

        // ensure a name is added
        if (result.getName() == null) {
            result.setName("From source " + connection.getSource().getName());
        }

        result.setMatches(matches);
        return result;
    }

    private ComponentSearchResultDTO search(final String searchStr, final RemoteProcessGroup group) {
        final List<String> matches = new ArrayList<>();
        addIfAppropriate(searchStr, group.getIdentifier(), "Id", matches);
        addIfAppropriate(searchStr, group.getName(), "Name", matches);
        addIfAppropriate(searchStr, group.getComments(), "Comments", matches);
        addIfAppropriate(searchStr, group.getTargetUri().toString(), "URL", matches);

        // consider the transmission status
        if ((StringUtils.containsIgnoreCase("transmitting", searchStr) || StringUtils.containsIgnoreCase("transmission enabled", searchStr)) && group.isTransmitting()) {
            matches.add("Transmission: On");
        } else if ((StringUtils.containsIgnoreCase("not transmitting", searchStr) || StringUtils.containsIgnoreCase("transmission disabled", searchStr)) && !group.isTransmitting()) {
            matches.add("Transmission: Off");
        }

        if (matches.isEmpty()) {
            return null;
        }

        final ComponentSearchResultDTO result = new ComponentSearchResultDTO();
        result.setId(group.getIdentifier());
        result.setName(group.getName());
        result.setMatches(matches);
        return result;
    }

    private ComponentSearchResultDTO search(final String searchStr, final Funnel funnel) {
        final List<String> matches = new ArrayList<>();
        addIfAppropriate(searchStr, funnel.getIdentifier(), "Id", matches);

        if (matches.isEmpty()) {
            return null;
        }

        final ComponentSearchResultDTO dto = new ComponentSearchResultDTO();
        dto.setId(funnel.getIdentifier());
        dto.setName(funnel.getName());
        dto.setMatches(matches);
        return dto;
    }

    private void addIfAppropriate(final String searchStr, final String value, final String label, final List<String> matches) {
        if (StringUtils.containsIgnoreCase(value, searchStr)) {
            matches.add(label + ": " + value);
        }
    }

    /*
     * setters
     */
    public void setFlowController(FlowController flowController) {
        this.flowController = flowController;
    }

    public void setProperties(NiFiProperties properties) {
        this.properties = properties;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    public void setFlowService(FlowService flowService) {
        this.flowService = flowService;
    }

    public void setDtoFactory(DtoFactory dtoFactory) {
        this.dtoFactory = dtoFactory;
    }
}
