/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.Map;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.hadoop.hbase.client.TableDescriptor;

/**
 * Get, remove and modify table descriptors.
 */
@InterfaceAudience.Private
public interface TableDescriptors {
  /**
   * @return TableDescriptor for tablename
   */
  TableDescriptor get(final TableName tableName) throws IOException;

  /**
   * Get Map of all NamespaceDescriptors for a given namespace.
   * @return Map of all descriptors.
   */
  Map<String, TableDescriptor> getByNamespace(String name) throws IOException;

  /**
   * Get Map of all TableDescriptors. Populates the descriptor cache as a
   * side effect.
   * Notice: the key of map is the table name which contains namespace. It was generated by
   * {@link TableName#getNameWithNamespaceInclAsString()}.
   * @return Map of all descriptors.
   */
  Map<String, TableDescriptor> getAll() throws IOException;

  /**
   * Add or update descriptor
   * @param htd Descriptor to set into TableDescriptors
   */
  void update(final TableDescriptor htd) throws IOException;

  /**
   * @return Instance of table descriptor or null if none found.
   */
  TableDescriptor remove(final TableName tablename) throws IOException;

  /**
   * Enables the tabledescriptor cache
   */
  void setCacheOn() throws IOException;

  /**
   * Disables the tabledescriptor cache
   */
  void setCacheOff() throws IOException;
}