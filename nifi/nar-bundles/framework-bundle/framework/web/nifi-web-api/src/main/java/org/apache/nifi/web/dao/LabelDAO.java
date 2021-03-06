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
package org.apache.nifi.web.dao;

import java.util.Set;
import org.apache.nifi.controller.label.Label;
import org.apache.nifi.web.api.dto.LabelDTO;

/**
 *
 */
public interface LabelDAO {

    /**
     * Determines if the specified label exists in the specified group.
     *
     * @param groupId
     * @param labelId
     * @return
     */
    boolean hasLabel(String groupId, String labelId);

    /**
     * Creates a label in the specified group.
     *
     * @param groupId
     * @param labelDTO The label DTO
     * @return The label
     */
    Label createLabel(String groupId, LabelDTO labelDTO);

    /**
     * Gets the specified label in the specified group.
     *
     * @param groupId
     * @param labelId The label id
     * @return The label
     */
    Label getLabel(String groupId, String labelId);

    /**
     * Gets all of the labels in the specified group.
     *
     * @param groupId
     * @return The labels
     */
    Set<Label> getLabels(String groupId);

    /**
     * Updates the specified label in the specified group.
     *
     * @param groupId
     * @param labelDTO The label DTO
     * @return The label
     */
    Label updateLabel(String groupId, LabelDTO labelDTO);

    /**
     * Deletes the specified label in the specified group.
     *
     * @param groupId
     * @param labelId The label id
     */
    void deleteLabel(String groupId, String labelId);
}
