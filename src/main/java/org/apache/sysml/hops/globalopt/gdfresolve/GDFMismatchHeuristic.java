/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.hops.globalopt.gdfresolve;

import org.apache.sysml.hops.globalopt.RewriteConfig;

public abstract class GDFMismatchHeuristic 
{

	public enum MismatchHeuristicType {
		FIRST,
		BLOCKSIZE_OR_FIRST,
	}
	
	/**
	 * Returns the name of the implementing mismatch heuristic.
	 * 
	 * @return the name of the implementing mismatch heuristic
	 */
	public abstract String getName();
	
	/**
	 * Resolve the mismatch of two given rewrite configurations. This call returns true,
	 * if and only if the new configuration is chosen.
	 * 
	 * @param currRc the current rewrite config
	 * @param newRc the new rewrite config
	 * @return true if and only if the new configuration is chosen
	 */
	public abstract boolean resolveMismatch( RewriteConfig currRc, RewriteConfig newRc );
}
