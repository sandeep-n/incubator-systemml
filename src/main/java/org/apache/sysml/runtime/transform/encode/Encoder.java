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

package org.apache.sysml.runtime.transform.encode;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.sysml.runtime.matrix.data.FrameBlock;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.transform.DistinctValue;
import org.apache.sysml.runtime.transform.TfUtils;
import org.apache.sysml.runtime.util.UtilFunctions;
import org.apache.wink.json4j.JSONArray;

/**
 * Base class for all transform encoders providing both a row and block
 * interface for decoding frames to matrices.
 * 
 */
public abstract class Encoder implements Serializable
{
	private static final long serialVersionUID = 2299156350718979064L;
	
	protected int _clen = -1; 
	protected int[] _colList = null;
	
	protected Encoder( int[] colList, int clen ) {
		_colList = colList;
		_clen = clen;
	}
	
	public int[] getColList() {
		return _colList;
	}
	
	public void setColList(int[] colList) {
		_colList = colList;
	}
	
	public int getNumCols() {
		return _clen;
	}

	public int initColList(JSONArray attrs) {
		_colList = new int[attrs.size()];
		for(int i=0; i < _colList.length; i++) 
			_colList[i] = UtilFunctions.toInt(attrs.get(i));	
		return _colList.length;
	}

	public int initColList(int[] colList) {
		_colList = colList;
		return _colList.length;
	}
	
	/**
	 * Indicates if this encoder is applicable, i.e, if there is at 
	 * least one column to encode. 
	 * 
	 * @return true if at least one column to encode
	 */
	public boolean isApplicable()  {
		return (_colList != null && _colList.length > 0);
	}
	
	/**
	 * Indicates if this encoder is applicable for the given column ID,
	 * i.e., if it is subject to this transformation.
	 * 
	 * @param colID column ID
	 * @return true if encoder is applicable for given column
	 */
	public int isApplicable(int colID) {
		if(_colList == null)
			return -1;
		int idx = Arrays.binarySearch(_colList, colID);
		return ( idx >= 0 ? idx : -1);
	}
	
	/**
	 * Block encode: build and apply (transform encode).
	 * 
	 * @param in input frame block
	 * @param out output matrix block
	 * @return output matrix block
	 */
	public abstract MatrixBlock encode(FrameBlock in, MatrixBlock out);

	/**
	 * Build the transform meta data for the given block input. This call modifies
	 * and keeps meta data as encoder state.
	 * 
	 * @param in input frame block
	 */
	public abstract void build(FrameBlock in);
	
	/**
	 * Encode input data blockwise according to existing transform meta
	 * data (transform apply).
	 * 
	 * @param in input frame block
	 * @param out output matrix block
	 * @return output matrix block
	 */
	public abstract MatrixBlock apply(FrameBlock in, MatrixBlock out);
	
	/**
	 * Encode input data according to existing transform meta
	 * data (transform apply).
	 * TODO remove once file-based transform removed
	 * 
	 * @param in input data as string array
	 * @return encoded data as string array
	 */
	public abstract String[] apply(String[] in);

	/**
	 * Construct a frame block out of the transform meta data.
	 * 
	 * @param out output frame block
	 * @return output frame block?
	 */
	public abstract FrameBlock getMetaData(FrameBlock out);

	/**
	 * Sets up the required meta data for a subsequent call to apply.
	 * 
	 * @param meta frame block
	 */
	public abstract void initMetaData(FrameBlock meta);
	
	
	//OLD API: kept for a transition phase only
	//TODO stage 2: refactor data and meta data IO into minimal set of ultility functions
	abstract public void mapOutputTransformationMetadata(OutputCollector<IntWritable, DistinctValue> out, int taskID, TfUtils agents) throws IOException;
	abstract public void mergeAndOutputTransformationMetadata(Iterator<DistinctValue> values, String outputDir, int colID, FileSystem fs, TfUtils agents) throws IOException;
	abstract public void loadTxMtd(JobConf job, FileSystem fs, Path txMtdDir, TfUtils agents) throws IOException;
}
