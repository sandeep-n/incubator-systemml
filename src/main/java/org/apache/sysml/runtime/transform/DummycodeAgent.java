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

package org.apache.sysml.runtime.transform;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.sysml.runtime.matrix.data.FrameBlock;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.transform.encode.Encoder;
import org.apache.sysml.runtime.transform.meta.TfMetaUtils;
import org.apache.sysml.runtime.util.UtilFunctions;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

public class DummycodeAgent extends Encoder 
{		
	private static final long serialVersionUID = 5832130477659116489L;

	private HashMap<Integer, HashMap<String,String>> _finalMaps = null;
	private HashMap<Integer, HashMap<String,Long>> _finalMapsCP = null;
	private int[] _binList = null;
	private int[] _numBins = null;
	
	private int[] _domainSizes = null;			// length = #of dummycoded columns
	private int[] _dcdColumnMap = null;			// to help in translating between original and dummycoded column IDs
	private long _dummycodedLength = 0;			// #of columns after dummycoded

	public DummycodeAgent(JSONObject parsedSpec, String[] colnames, int clen) throws JSONException {
		super(null, clen);
		
		if ( parsedSpec.containsKey(TfUtils.TXMETHOD_DUMMYCODE) ) {
			int[] collist = TfMetaUtils.parseJsonIDList(parsedSpec, colnames, TfUtils.TXMETHOD_DUMMYCODE);
			initColList(collist);
		}
	}
	
	@Override
	public int getNumCols() {
		return (int)_dummycodedLength;
	}
	
	/**
	 * Method to output transformation metadata from the mappers. 
	 * This information is collected and merged by the reducers.
	 */
	@Override
	public void mapOutputTransformationMetadata(OutputCollector<IntWritable, DistinctValue> out, int taskID, TfUtils agents) throws IOException {
		// There is no metadata required for dummycode.
		// Required information is output from RecodeAgent.
		return;
	}
	
	@Override
	public void mergeAndOutputTransformationMetadata(Iterator<DistinctValue> values,
			String outputDir, int colID, FileSystem fs, TfUtils agents) throws IOException {
		// Nothing to do here
	}

	public void setRecodeMaps(HashMap<Integer, HashMap<String,String>> maps) {
		_finalMaps = maps;
	}
	
	public void setRecodeMapsCP(HashMap<Integer, HashMap<String,Long>> maps) {
		_finalMapsCP = maps;
	}
	
	public void setNumBins(int[] binList, int[] numbins) {
		_binList = binList;
		_numBins = numbins;
	}
	
	/**
	 * Method to generate dummyCodedMaps.csv, with the range of column IDs for each variable in the original data.
	 * 
	 * Each line in dummyCodedMaps.csv file is of the form: [ColID, 1/0, st, end]
	 * 		1/0 indicates if ColID is dummycoded or not
	 * 		[st,end] is the range of dummycoded column numbers for the given ColID
	 * 
	 * It also generates coltypes.csv, with the type (scale, nominal, etc.) of columns in the output.
	 * Recoded columns are of type nominal, binner columns are of type ordinal, dummycoded columns are of type 
	 * dummycoded, and the remaining are of type scale.
	 * 
	 * @param fs file system
	 * @param txMtdDir path to transform metadata directory
	 * @param numCols number of columns
	 * @param agents ?
	 * @return ?
	 * @throws IOException if IOException occurs
	 */
	public int genDcdMapsAndColTypes(FileSystem fs, String txMtdDir, int numCols, TfUtils agents) throws IOException {
		
		// initialize all column types in the transformed data to SCALE
		TfUtils.ColumnTypes[] ctypes = new TfUtils.ColumnTypes[(int) _dummycodedLength];
		for(int i=0; i < _dummycodedLength; i++)
			ctypes[i] = TfUtils.ColumnTypes.SCALE;
		
		_dcdColumnMap = new int[numCols];

		Path pt=new Path(txMtdDir+"/Dummycode/" + TfUtils.DCD_FILE_NAME);
		BufferedWriter br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		
		int sum=1;
		int idx = 0;
		for(int colID=1; colID <= numCols; colID++) 
		{
			if ( _colList != null && idx < _colList.length && _colList[idx] == colID )
			{
				br.write(colID + TfUtils.TXMTD_SEP + "1" + TfUtils.TXMTD_SEP + sum + TfUtils.TXMTD_SEP + (sum+_domainSizes[idx]-1) + "\n");
				_dcdColumnMap[colID-1] = (sum+_domainSizes[idx]-1)-1;

				for(int i=sum; i <=(sum+_domainSizes[idx]-1); i++)
					ctypes[i-1] = TfUtils.ColumnTypes.DUMMYCODED;
				
				sum += _domainSizes[idx];
				idx++;
			}
			else 
			{
				br.write(colID + TfUtils.TXMTD_SEP + "0" + TfUtils.TXMTD_SEP + sum + TfUtils.TXMTD_SEP + sum + "\n");
				_dcdColumnMap[colID-1] = sum-1;
				
				if ( agents.getBinAgent().isApplicable(colID) != -1 )
					ctypes[sum-1] = TfUtils.ColumnTypes.ORDINAL;	// binned variable results in an ordinal column
				
				if ( agents.getRecodeAgent().isApplicable(colID) != -1 )
					ctypes[sum-1] = TfUtils.ColumnTypes.NOMINAL;
				
				sum += 1;
			}
		}
		br.close();

		// Write coltypes.csv
		pt=new Path(txMtdDir + File.separator + TfUtils.TXMTD_COLTYPES);
		br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		
		br.write(ctypes[0].toID() + "");
		for(int i = 1; i < _dummycodedLength; i++) 
			br.write( TfUtils.TXMTD_SEP + ctypes[i].toID() );
		br.close();
		
		return sum-1;
	}
	
	/**
	 * Given a dummycoded column id, find the corresponding original column ID.
	 *  
	 * @param colID dummycoded column ID
	 * @return original column ID, -1 if not found
	 */
	public int mapDcdColumnID(int colID) 
	{
		for(int i=0; i < _dcdColumnMap.length; i++)
		{
			int st = (i==0 ? 1 : _dcdColumnMap[i-1]+1+1);
			int end = _dcdColumnMap[i]+1;
			//System.out.println((i+1) + ": " + "[" + st + "," + end + "]");
			
			if ( colID >= st && colID <= end)
				return i+1;
		}
		return -1;
	}
	
	public String constructDummycodedHeader(String header, Pattern delim) {
		
		if(_colList == null && _binList == null )
			// none of the columns are dummycoded, simply return the given header
			return header;
		
		String[] names = delim.split(header, -1);
		List<String> newNames = null;
		
		StringBuilder sb = new StringBuilder();
		
		// Dummycoding can be performed on either on a recoded column or on a binned column
		
		// process recoded columns
		if(_finalMapsCP != null && _colList != null) 
		{
			for(int i=0; i <_colList.length; i++) 
			{
				int colID = _colList[i];
				HashMap<String,Long> map = _finalMapsCP.get(colID);
				String colName = UtilFunctions.unquote(names[colID-1]);
				
				if ( map != null  ) 
				{
					// order map entries by their recodeID
					List<Map.Entry<String, Long>> entryList = new ArrayList<Map.Entry<String, Long>>(map.entrySet());
					Comparator<Map.Entry<String, Long>> comp = new Comparator<Map.Entry<String, Long>>() {
						@Override
						public int compare(Entry<String, Long> entry1, Entry<String, Long> entry2) {
							Long value1 = entry1.getValue();
							Long value2 = entry2.getValue();
							return (int) (value1 - value2);
						}
					};
					Collections.sort(entryList, comp);
					newNames = new ArrayList<String>();
					for (Entry<String, Long> entry : entryList) {
						newNames.add(entry.getKey());
					}
					
					// construct concatenated string of map entries
					sb.setLength(0);
					for(int idx=0; idx < newNames.size(); idx++) 
					{
						if(idx==0) 
							sb.append( colName + TfUtils.DCD_NAME_SEP + newNames.get(idx));
						else
							sb.append( delim + colName + TfUtils.DCD_NAME_SEP + newNames.get(idx));
					}
					names[colID-1] = sb.toString();			// replace original column name with dcd name
				}
			}
		}
		else if(_finalMaps != null && _colList != null) {
			for(int i=0; i <_colList.length; i++) {
				int colID = _colList[i];
				HashMap<String,String> map = _finalMaps.get(colID);
				String colName = UtilFunctions.unquote(names[colID-1]);
				
				if ( map != null ) 
				{
					
					// order map entries by their recodeID (represented as Strings .. "1", "2", etc.)
					List<Map.Entry<String, String>> entryList = new ArrayList<Map.Entry<String, String>>(map.entrySet());
					Comparator<Map.Entry<String, String>> comp = new Comparator<Map.Entry<String, String>>() {
						@Override
						public int compare(Entry<String, String> entry1, Entry<String, String> entry2) {
							String value1 = entry1.getValue();
							String value2 = entry2.getValue();
							return (Integer.parseInt(value1) - Integer.parseInt(value2));
						}
					};
					Collections.sort(entryList, comp);
					newNames = new ArrayList<String>();
					for (Entry<String, String> entry : entryList) {
						newNames.add(entry.getKey());
					}
					
					// construct concatenated string of map entries
					sb.setLength(0);
					for(int idx=0; idx < newNames.size(); idx++) 
					{
						if(idx==0) 
							sb.append( colName + TfUtils.DCD_NAME_SEP + newNames.get(idx));
						else
							sb.append( delim + colName + TfUtils.DCD_NAME_SEP + newNames.get(idx));
					}
					names[colID-1] = sb.toString();			// replace original column name with dcd name
				}
			}
		}
		
		// process binned columns
		if (_binList != null) 
			for(int i=0; i < _binList.length; i++) 
			{
				int colID = _binList[i];
				
				// need to consider only binned and dummycoded columns
				if(isApplicable(colID) == -1)
					continue;
				
				int numBins = _numBins[i];
				String colName = UtilFunctions.unquote(names[colID-1]);
				
				sb.setLength(0);
				for(int idx=0; idx < numBins; idx++) 
					if(idx==0) 
						sb.append( colName + TfUtils.DCD_NAME_SEP + "Bin" + (idx+1) );
					else
						sb.append( delim + colName + TfUtils.DCD_NAME_SEP + "Bin" + (idx+1) );
				names[colID-1] = sb.toString();			// replace original column name with dcd name
			}
		
		// Construct the full header
		sb.setLength(0);
		for(int colID=0; colID < names.length; colID++) 
		{
			if (colID == 0)
				sb.append(names[colID]);
			else
				sb.append(delim + names[colID]);
		}
		//System.out.println("DummycodedHeader: " + sb.toString());
		
		return sb.toString();
	}
	
	@Override
	public void loadTxMtd(JobConf job, FileSystem fs, Path txMtdDir, TfUtils agents) throws IOException {
		if ( !isApplicable() ) {
			_dummycodedLength = _clen;
			return;
		}
		
		// sort to-be dummycoded column IDs in ascending order. This is the order in which the new dummycoded record is constructed in apply() function.
		Arrays.sort(_colList);	
		_domainSizes = new int[_colList.length];

		_dummycodedLength = _clen;
		
		//HashMap<String, String> map = null;
		for(int i=0; i<_colList.length; i++) {
			int colID = _colList[i];
			
			// Find the domain size for colID using _finalMaps or _finalMapsCP
			int domainSize = 0;
			if(_finalMaps != null) {
				if(_finalMaps.get(colID) != null)
					domainSize = _finalMaps.get(colID).size();
			}
			else {
				if(_finalMapsCP.get(colID) != null)
					domainSize = _finalMapsCP.get(colID).size();
			}
			
			if ( domainSize != 0 ) {
				// dummycoded column
				_domainSizes[i] = domainSize;
			}
			else {
				// binned column
				if ( _binList != null )
				for(int j=0; j<_binList.length; j++) {
					if (colID == _binList[j]) {
						_domainSizes[i] = _numBins[j];
						break;
					}
				}
			}
			_dummycodedLength += _domainSizes[i]-1;
		}
	}


	@Override
	public MatrixBlock encode(FrameBlock in, MatrixBlock out) {
		return apply(in, out);
	}

	@Override
	public void build(FrameBlock in) {
		//do nothing
	}
	
	/**
	 * Method to apply transformations.
	 * 
	 * @param words array of strings
	 * @return array of transformed strings
	 */
	@Override
	public String[] apply(String[] words) 
	{
		if( !isApplicable() )
			return words;
		
		String[] nwords = new String[(int)_dummycodedLength];
		int rcdVal = 0;
		
		for(int colID=1, idx=0, ncolID=1; colID <= words.length; colID++) {
			if(idx < _colList.length && colID==_colList[idx]) {
				// dummycoded columns
				try {
					rcdVal = UtilFunctions.parseToInt(UtilFunctions.unquote(words[colID-1]));
					nwords[ ncolID-1+rcdVal-1 ] = "1";
					ncolID += _domainSizes[idx];
					idx++;
				} 
				catch (Exception e) {
					throw new RuntimeException("Error in dummycoding: colID="+colID + ", rcdVal=" + rcdVal+", word="+words[colID-1] 
							+ ", domainSize=" + _domainSizes[idx] + ", dummyCodedLength=" + _dummycodedLength);
				}
			}
			else {
				nwords[ncolID-1] = words[colID-1];
				ncolID++;
			}
		}
		
		return nwords;
	}
	
	@Override
	public MatrixBlock apply(FrameBlock in, MatrixBlock out) 
	{
		MatrixBlock ret = new MatrixBlock(out.getNumRows(), (int)_dummycodedLength, false);
		
		for( int i=0; i<out.getNumRows(); i++ ) {
			for(int colID=1, idx=0, ncolID=1; colID <= out.getNumColumns(); colID++) {
				double val = out.quickGetValue(i, colID-1);
				if(idx < _colList.length && colID==_colList[idx]) {
					ret.quickSetValue(i, ncolID-1+(int)val-1, 1);
					ncolID += _domainSizes[idx];
					idx++;
				}
				else {
					double ptval = UtilFunctions.objectToDouble(in.getSchema()[colID-1], in.get(i, colID-1));
					ret.quickSetValue(i, ncolID-1, ptval);
					ncolID++;
				}
			}
		}
		
		return ret;
	}

	@Override
	public FrameBlock getMetaData(FrameBlock out) {
		return out;
	}
	
	@Override
	public void initMetaData(FrameBlock meta) {
		//initialize domain sizes and output num columns
		_domainSizes = new int[_colList.length];
		_dummycodedLength = _clen;
		for( int j=0; j<_colList.length; j++ ) {
			int colID = _colList[j]; //1-based
			_domainSizes[j] = (int)meta.getColumnMetadata()[colID-1].getNumDistinct();
			_dummycodedLength +=  _domainSizes[j]-1;
		}
	}
}
