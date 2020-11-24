/**
 *
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
package org.apache.hadoop.hbase.master.normalizer;

import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.yetus.audience.InterfaceAudience;
import java.io.IOException;

/**
 * Interface for normalization plan.
 */
@InterfaceAudience.Private
public interface NormalizationPlan {
  enum PlanType {
    SPLIT,
    MERGE,
    NONE
  }

  /**
   * Submits normalization plan on cluster (does actual splitting/merging work) and
   * returns proc Id to caller.
   * @param masterServices instance of {@link MasterServices}
   * @return Proc Id for the submitted task
   * @throws IOException If plan submission to Admin fails
   */
  long submit(MasterServices masterServices) throws IOException;

  /**
   * @return the type of this plan
   */
  PlanType getType();
}
