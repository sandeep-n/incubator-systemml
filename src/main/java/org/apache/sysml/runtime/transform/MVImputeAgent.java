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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.functionobjects.CM;
import org.apache.sysml.runtime.functionobjects.KahanPlus;
import org.apache.sysml.runtime.functionobjects.Mean;
import org.apache.sysml.runtime.instructions.cp.CM_COV_Object;
import org.apache.sysml.runtime.instructions.cp.KahanObject;
import org.apache.sysml.runtime.matrix.data.FrameBlock;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.Pair;
import org.apache.sysml.runtime.matrix.operators.CMOperator;
import org.apache.sysml.runtime.matrix.operators.CMOperator.AggregateOperationTypes;
import org.apache.sysml.runtime.transform.encode.Encoder;
import org.apache.sysml.runtime.transform.meta.TfMetaUtils;
import org.apache.sysml.runtime.util.UtilFunctions;

public class MVImputeAgent extends Encoder 
{	
	private static final long serialVersionUID = 9057868620144662194L;

	public static final String MEAN_PREFIX = "mean";
	public static final String VARIANCE_PREFIX = "var";
	public static final String CORRECTION_PREFIX = "correction";
	public static final String COUNT_PREFIX = "validcount";		// #of valid or non-missing values in a column
	public static final String TOTAL_COUNT_PREFIX = "totalcount";	// #of total records processed by a mapper
	public static final String CONSTANT_PREFIX = "constant";
	
	public enum MVMethod { INVALID, GLOBAL_MEAN, GLOBAL_MODE, CONSTANT };
	
	private MVMethod[] _mvMethodList = null;
	private MVMethod[] _mvscMethodList = null;	// scaling methods for attributes that are imputed and also scaled
	
	private BitSet _isMVScaled = null;
	private CM _varFn = CM.getCMFnObject(AggregateOperationTypes.VARIANCE);		// function object that understands variance computation
	
	// objects required to compute mean and variance of all non-missing entries 
	private Mean _meanFn = Mean.getMeanFnObject();	// function object that understands mean computation
	private KahanObject[] _meanList = null; 		// column-level means, computed so far
	private long[] _countList = null;				// #of non-missing values
	
	private CM_COV_Object[] _varList = null;		// column-level variances, computed so far (for scaling)

	private int[] 			_scnomvList = null;			// List of attributes that are scaled but not imputed
	private MVMethod[]		_scnomvMethodList = null;	// scaling methods: 0 for invalid; 1 for mean-subtraction; 2 for z-scoring
	private KahanObject[] 	_scnomvMeanList = null;		// column-level means, for attributes scaled but not imputed
	private long[] 			_scnomvCountList = null;	// #of non-missing values, for attributes scaled but not imputed
	private CM_COV_Object[] _scnomvVarList = null;		// column-level variances, computed so far
	
	private String[] _replacementList = null;		// replacements: for global_mean, mean; and for global_mode, recode id of mode category
	private String[] _NAstrings = null;
	private List<Integer> _rcList = null; 
	private HashMap<Integer,HashMap<String,Long>> _hist = null;
	
	public String[] getReplacements() { return _replacementList; }
	public KahanObject[] getMeans()   { return _meanList; }
	public CM_COV_Object[] getVars()  { return _varList; }
	public KahanObject[] getMeans_scnomv()   { return _scnomvMeanList; }
	public CM_COV_Object[] getVars_scnomv()  { return _scnomvVarList; }
	
	public MVImputeAgent(JSONObject parsedSpec, String[] colnames, int clen) 
		throws JSONException
	{
		super(null, clen);
		
		//handle column list
		int[] collist = TfMetaUtils.parseJsonObjectIDList(parsedSpec, colnames, TfUtils.TXMETHOD_IMPUTE);
		initColList(collist);
	
		//handle method list
		parseMethodsAndReplacments(parsedSpec);
		
		//create reuse histograms
		_hist = new HashMap<Integer, HashMap<String,Long>>();
	}
			
	public MVImputeAgent(JSONObject parsedSpec, String[] colnames, String[] NAstrings, int clen)
		throws JSONException 
	{
		super(null, clen);	
		boolean isMV = parsedSpec.containsKey(TfUtils.TXMETHOD_IMPUTE);
		boolean isSC = parsedSpec.containsKey(TfUtils.TXMETHOD_SCALE);		
		_NAstrings = NAstrings;
		
		if(!isMV) {
			// MV Impute is not applicable
			_colList = null;
			_mvMethodList = null;
			_meanList = null;
			_countList = null;
			_replacementList = null;
		}
		else {
			JSONObject mvobj = (JSONObject) parsedSpec.get(TfUtils.TXMETHOD_IMPUTE);
			JSONArray mvattrs = (JSONArray) mvobj.get(TfUtils.JSON_ATTRS);
			JSONArray mvmthds = (JSONArray) mvobj.get(TfUtils.JSON_MTHD);
			int mvLength = mvattrs.size();
			
			_colList = new int[mvLength];
			_mvMethodList = new MVMethod[mvLength];
			
			_meanList = new KahanObject[mvLength];
			_countList = new long[mvLength];
			_varList = new CM_COV_Object[mvLength];
			
			_isMVScaled = new BitSet(_colList.length);
			_isMVScaled.clear();
			
			for(int i=0; i < _colList.length; i++) {
				_colList[i] = UtilFunctions.toInt(mvattrs.get(i));
				_mvMethodList[i] = MVMethod.values()[UtilFunctions.toInt(mvmthds.get(i))]; 
				_meanList[i] = new KahanObject(0, 0);
			}
			
			_replacementList = new String[mvLength]; 	// contains replacements for all columns (scale and categorical)
			
			JSONArray constants = (JSONArray)mvobj.get(TfUtils.JSON_CONSTS);
			for(int i=0; i < constants.size(); i++) {
				if ( constants.get(i) == null )
					_replacementList[i] = "NaN";
				else
					_replacementList[i] = constants.get(i).toString();
			}
		}
		
		// Handle scaled attributes
		if ( !isSC )
		{
			// scaling is not applicable
			_scnomvCountList = null;
			_scnomvMeanList = null;
			_scnomvVarList = null;
		}
		else
		{
			if ( _colList != null ) 
				_mvscMethodList = new MVMethod[_colList.length];
			
			JSONObject scobj = (JSONObject) parsedSpec.get(TfUtils.TXMETHOD_SCALE);
			JSONArray scattrs = (JSONArray) scobj.get(TfUtils.JSON_ATTRS);
			JSONArray scmthds = (JSONArray) scobj.get(TfUtils.JSON_MTHD);
			int scLength = scattrs.size();
			
			int[] _allscaled = new int[scLength];
			int scnomv = 0, colID;
			byte mthd;
			for(int i=0; i < scLength; i++)
			{
				colID = UtilFunctions.toInt(scattrs.get(i));
				mthd = (byte) UtilFunctions.toInt(scmthds.get(i)); 
						
				_allscaled[i] = colID;
				
				// check if the attribute is also MV imputed
				int mvidx = isApplicable(colID);
				if(mvidx != -1)
				{
					_isMVScaled.set(mvidx);
					_mvscMethodList[mvidx] = MVMethod.values()[mthd];
					_varList[mvidx] = new CM_COV_Object();
				}
				else
					scnomv++;	// count of scaled but not imputed 
			}
			
			if(scnomv > 0)
			{
				_scnomvList = new int[scnomv];			
				_scnomvMethodList = new MVMethod[scnomv];	
	
				_scnomvMeanList = new KahanObject[scnomv];
				_scnomvCountList = new long[scnomv];
				_scnomvVarList = new CM_COV_Object[scnomv];
				
				for(int i=0, idx=0; i < scLength; i++)
				{
					colID = UtilFunctions.toInt(scattrs.get(i));
					mthd = (byte)UtilFunctions.toInt(scmthds.get(i)); 
							
					if(isApplicable(colID) == -1)
					{	// scaled but not imputed
						_scnomvList[idx] = colID;
						_scnomvMethodList[idx] = MVMethod.values()[mthd];
						_scnomvMeanList[idx] = new KahanObject(0, 0);
						_scnomvVarList[idx] = new CM_COV_Object();
						idx++;
					}
				}
			}
		}
	}

	private void parseMethodsAndReplacments(JSONObject parsedSpec) throws JSONException {
		JSONArray mvspec = (JSONArray) parsedSpec.get(TfUtils.TXMETHOD_IMPUTE);
		_mvMethodList = new MVMethod[mvspec.size()];
		_replacementList = new String[mvspec.size()];
		_meanList = new KahanObject[mvspec.size()];
		_countList = new long[mvspec.size()];
		for(int i=0; i < mvspec.size(); i++) {
			JSONObject mvobj = (JSONObject)mvspec.get(i);
			_mvMethodList[i] = MVMethod.valueOf(mvobj.get("method").toString().toUpperCase()); 
			if( _mvMethodList[i] == MVMethod.CONSTANT ) {
				_replacementList[i] = mvobj.getString("value").toString();
			}
			_meanList[i] = new KahanObject(0, 0);
		}
	}
	
	
	public void prepare(String[] words) throws IOException {
		
		try {
			String w = null;
			if(_colList != null)
			for(int i=0; i <_colList.length; i++) {
				int colID = _colList[i];
				w = UtilFunctions.unquote(words[colID-1].trim());
				
				try {
				if(!TfUtils.isNA(_NAstrings, w)) {
					_countList[i]++;
					
					boolean computeMean = (_mvMethodList[i] == MVMethod.GLOBAL_MEAN || _isMVScaled.get(i) );
					if(computeMean) {
						// global_mean
						double d = UtilFunctions.parseToDouble(w);
						_meanFn.execute2(_meanList[i], d, _countList[i]);
						
						if (_isMVScaled.get(i) && _mvscMethodList[i] == MVMethod.GLOBAL_MODE)
							_varFn.execute(_varList[i], d);
					}
					else {
						// global_mode or constant
						// Nothing to do here. Mode is computed using recode maps.
					}
				}
				} catch (NumberFormatException e) 
				{
					throw new RuntimeException("Encountered \"" + w + "\" in column ID \"" + colID + "\", when expecting a numeric value. Consider adding \"" + w + "\" to na.strings, along with an appropriate imputation method.");
				}
			}
			
			// Compute mean and variance for attributes that are scaled but not imputed
			if(_scnomvList != null)
			for(int i=0; i < _scnomvList.length; i++) 
			{
				int colID = _scnomvList[i];
				w = UtilFunctions.unquote(words[colID-1].trim());
				double d = UtilFunctions.parseToDouble(w);
				_scnomvCountList[i]++; 		// not required, this is always equal to total #records processed
				_meanFn.execute2(_scnomvMeanList[i], d, _scnomvCountList[i]);
				if(_scnomvMethodList[i] == MVMethod.GLOBAL_MODE)
					_varFn.execute(_scnomvVarList[i], d);
			}
		} catch(Exception e) {
			throw new IOException(e);
		}
	}
	
	// ----------------------------------------------------------------------------------------------------------
	
	private String encodeCMObj(CM_COV_Object obj)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(obj.w);
		sb.append(",");
		sb.append(obj.mean._sum);
		sb.append(",");
		sb.append(obj.mean._correction);
		sb.append(",");
		sb.append(obj.m2._sum);
		sb.append(",");
		sb.append(obj.m2._correction);
		return sb.toString();
	}
	
	private CM_COV_Object decodeCMObj(String s) 
	{
		CM_COV_Object obj = new CM_COV_Object();
		String[] parts = s.split(",");
		obj.w = UtilFunctions.parseToDouble(parts[0]);
		obj.mean._sum = UtilFunctions.parseToDouble(parts[1]);
		obj.mean._correction = UtilFunctions.parseToDouble(parts[2]);
		obj.m2._sum = UtilFunctions.parseToDouble(parts[3]);
		obj.m2._correction = UtilFunctions.parseToDouble(parts[4]);
		
		return obj;
	}
	
	private DistinctValue prepMeanOutput(int taskID, int idx, StringBuilder sb, boolean scnomv) throws CharacterCodingException {
		
		MVMethod mthd = (scnomv ? _scnomvMethodList[idx] : _mvMethodList[idx]);
		
		if ( scnomv || mthd == MVMethod.GLOBAL_MEAN || _isMVScaled.get(idx) ) {
			String suffix = null;
			if(scnomv)
				suffix = "scnomv";
			else if ( mthd == MVMethod.GLOBAL_MEAN && _isMVScaled.get(idx) )
				suffix = "scmv"; 	// both scaled and mv imputed
			else if ( mthd == MVMethod.GLOBAL_MEAN )
				suffix = "noscmv";
			else
				suffix = "scnomv";
			
			sb.setLength(0);
			sb.append(MEAN_PREFIX);
			sb.append("_");
			sb.append(taskID);
			sb.append("_");
			double mean = (scnomv ? _scnomvMeanList[idx]._sum : _meanList[idx]._sum);
			sb.append(Double.toString(mean));
			sb.append(",");
			sb.append(suffix);
			//String s = MEAN_PREFIX + "_" + taskID + "_" + Double.toString(_meanList[idx]._sum) + "," + suffix;
			return new DistinctValue(sb.toString(), -1L);
		}
		
		return null;
	}
	
	private DistinctValue prepMeanCorrectionOutput(int taskID, int idx, StringBuilder sb, boolean scnomv) throws CharacterCodingException {
		MVMethod mthd = (scnomv ? _scnomvMethodList[idx] : _mvMethodList[idx]);
		if ( scnomv || mthd == MVMethod.GLOBAL_MEAN || _isMVScaled.get(idx) ) {
			sb.setLength(0);
			//CORRECTION_PREFIX + "_" + taskID + "_" + Double.toString(mean._correction);
			sb.append(CORRECTION_PREFIX);
			sb.append("_");
			sb.append(taskID);
			sb.append("_");
			double corr = (scnomv ? _scnomvMeanList[idx]._correction : _meanList[idx]._correction);
			sb.append(Double.toString(corr));
			return new DistinctValue(sb.toString(), -1L);
		}
		return null;
	}
	
	private DistinctValue prepMeanCountOutput(int taskID, int idx, StringBuilder sb, boolean scnomv) throws CharacterCodingException {
		MVMethod mthd = (scnomv ? _scnomvMethodList[idx] : _mvMethodList[idx]);
		if ( scnomv || mthd == MVMethod.GLOBAL_MEAN || _isMVScaled.get(idx) ) {
			sb.setLength(0);
			//s = COUNT_PREFIX + "_" + taskID + "_" + Long.toString(count);
			sb.append(COUNT_PREFIX);
			sb.append("_");
			sb.append(taskID);
			sb.append("_");
			long count = (scnomv ? _scnomvCountList[idx] : _countList[idx]);
			sb.append( Long.toString(count));
			return new DistinctValue(sb.toString(), -1L);
		}
		return null;
	}
	
	private DistinctValue prepTotalCountOutput(int taskID, int idx, StringBuilder sb, boolean scnomv, TfUtils agents) throws CharacterCodingException {
		MVMethod mthd = (scnomv ? _scnomvMethodList[idx] : _mvMethodList[idx]);
		if ( scnomv || mthd == MVMethod.GLOBAL_MEAN || _isMVScaled.get(idx) ) {
			sb.setLength(0);
			//TOTAL_COUNT_PREFIX + "_" + taskID + "_" + Long.toString(TransformationAgent._numValidRecords);
			sb.append(TOTAL_COUNT_PREFIX);
			sb.append("_");
			sb.append(taskID);
			sb.append("_");
			sb.append( Long.toString(agents.getValid()) );
			return new DistinctValue(sb.toString(), -1L);
		}
		return null;
	}
	
	private DistinctValue prepConstantOutput(int idx, StringBuilder sb) throws CharacterCodingException {
		if ( _mvMethodList == null )
			return null;
		MVMethod mthd = _mvMethodList[idx];
		if ( mthd == MVMethod.CONSTANT ) {
			sb.setLength(0);
			sb.append(CONSTANT_PREFIX);
			sb.append("_");
			sb.append(_replacementList[idx]);
			return new DistinctValue(sb.toString(), -1);
		}
		return null;
	}
	
	private DistinctValue prepVarOutput(int taskID, int idx, StringBuilder sb, boolean scnomv) throws CharacterCodingException {
		if ( scnomv || _isMVScaled.get(idx) && _mvscMethodList[idx] == MVMethod.GLOBAL_MODE ) {
			sb.setLength(0);
			sb.append(VARIANCE_PREFIX);
			sb.append("_");
			sb.append(taskID);
			sb.append("_");
			CM_COV_Object cm = (scnomv ? _scnomvVarList[idx] : _varList[idx]);
			sb.append(encodeCMObj(cm));
		
			return new DistinctValue(sb.toString(), -1L);
		}
		return null;
	}
	
	private void outDV(IntWritable iw, DistinctValue dv, OutputCollector<IntWritable, DistinctValue> out) throws IOException {
		if ( dv != null )	
			out.collect(iw, dv);
	}
	
	/**
	 * Method to output transformation metadata from the mappers. 
	 * This information is collected and merged by the reducers.
	 */
	@Override
	public void mapOutputTransformationMetadata(OutputCollector<IntWritable, DistinctValue> out, int taskID, TfUtils agents) throws IOException {
		try { 
			StringBuilder sb = new StringBuilder();
			DistinctValue dv = null;
			
			if(_colList != null)
				for(int i=0; i < _colList.length; i++) {
					int colID = _colList[i];
					IntWritable iw = new IntWritable(-colID);
					
					dv = prepMeanOutput(taskID, i, sb, false);				outDV(iw, dv, out);
					dv = prepMeanCorrectionOutput(taskID, i, sb, false);	outDV(iw, dv, out);
					dv = prepMeanCountOutput(taskID, i, sb, false);			outDV(iw, dv, out);
					dv = prepTotalCountOutput(taskID, i, sb, false, agents); outDV(iw, dv, out);
					
					dv = prepConstantOutput(i, sb);							outDV(iw, dv, out);
					
					// output variance information relevant to scaling
					dv = prepVarOutput(taskID, i, sb, false);				outDV(iw, dv, out);
				}
			
			// handle attributes that are scaled but not imputed
			if(_scnomvList != null)
				for(int i=0; i < _scnomvList.length; i++)
				{
					int colID = _scnomvList[i];
					IntWritable iw = new IntWritable(-colID);
					
					dv = prepMeanOutput(taskID, i, sb, true);				outDV(iw, dv, out);
					dv = prepMeanCorrectionOutput(taskID, i, sb, true);		outDV(iw, dv, out);
					dv = prepMeanCountOutput(taskID, i, sb, true);			outDV(iw, dv, out);
					dv = prepTotalCountOutput(taskID, i, sb, true, agents);	outDV(iw, dv, out);
					
					dv = prepVarOutput(taskID, i, sb, true);				outDV(iw, dv, out); 
				}
		} catch(Exception e) {
			throw new IOException(e);
		}
	}
	
	/**
	 * Applicable when running on SPARK.
	 * Helper function to output transformation metadata into shuffle.
	 * 
	 * @param iw integer value
	 * @param dv distinct value
	 * @param list list of integer-distinct value pairs
	 * @throws IOException if IOException occurs
	 */
	private void addDV(Integer iw, DistinctValue dv, ArrayList<Pair<Integer, DistinctValue>> list) throws IOException {
		if ( dv != null )	
			list.add( new Pair<Integer, DistinctValue>(iw, dv) );	
	}

	public ArrayList<Pair<Integer, DistinctValue>> mapOutputTransformationMetadata(int taskID, ArrayList<Pair<Integer, DistinctValue>> list, TfUtils agents) throws IOException {
		try { 
			StringBuilder sb = new StringBuilder();
			DistinctValue dv = null;
			
			if(_colList != null)
				for(int i=0; i < _colList.length; i++) {
					int colID = _colList[i];
					Integer iw = -colID;
					
					dv = prepMeanOutput(taskID, i, sb, false);				addDV(iw, dv, list);
					dv = prepMeanCorrectionOutput(taskID, i, sb, false);	addDV(iw, dv, list);
					dv = prepMeanCountOutput(taskID, i, sb, false);			addDV(iw, dv, list);
					dv = prepTotalCountOutput(taskID, i, sb, false, agents); addDV(iw, dv, list);
					
					dv = prepConstantOutput(i, sb);							addDV(iw, dv, list);
					
					// output variance information relevant to scaling
					dv = prepVarOutput(taskID, i, sb, false);				addDV(iw, dv, list);
				}
			
			// handle attributes that are scaled but not imputed
			if(_scnomvList != null)
				for(int i=0; i < _scnomvList.length; i++)
				{
					int colID = _scnomvList[i];
					Integer iw = -colID;
					
					dv = prepMeanOutput(taskID, i, sb, true);				addDV(iw, dv, list);
					dv = prepMeanCorrectionOutput(taskID, i, sb, true);		addDV(iw, dv, list);
					dv = prepMeanCountOutput(taskID, i, sb, true);			addDV(iw, dv, list);
					dv = prepTotalCountOutput(taskID, i, sb, true, agents);	addDV(iw, dv, list);
					
					dv = prepVarOutput(taskID, i, sb, true);				addDV(iw, dv, list); 
				}
		} catch(Exception e) {
			throw new IOException(e);
		}
		return list;
	}
	
	// ----------------------------------------------------------------------------------------------------------
	
	private void writeTfMtd(int colID, String mean, String tfMtdDir, FileSystem fs, TfUtils agents) throws IOException 
	{
		Path pt=new Path(tfMtdDir+"/Impute/"+ agents.getName(colID) + TfUtils.TXMTD_MV_FILE_SUFFIX);
		BufferedWriter br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		br.write(colID + TfUtils.TXMTD_SEP + mean + "\n");
		br.close();
	}
	
	private void writeTfMtd(int colID, String mean, String sdev, String tfMtdDir, FileSystem fs, TfUtils agents) throws IOException 
	{
		Path pt=new Path(tfMtdDir+"/Scale/"+ agents.getName(colID) + TfUtils.SCALE_FILE_SUFFIX);
		BufferedWriter br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		br.write(colID + TfUtils.TXMTD_SEP + mean + TfUtils.TXMTD_SEP + sdev + "\n");
		br.close();
	}
	
	private void writeTfMtd(int colID, String min, String max, String binwidth, String nbins, String tfMtdDir, FileSystem fs, TfUtils agents) throws IOException 
	{
		Path pt = new Path(tfMtdDir+"/Bin/"+ agents.getName(colID) + TfUtils.TXMTD_BIN_FILE_SUFFIX);
		BufferedWriter br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		br.write(colID + TfUtils.TXMTD_SEP + min + TfUtils.TXMTD_SEP + max + TfUtils.TXMTD_SEP + binwidth + TfUtils.TXMTD_SEP + nbins + "\n");
		br.close();
	}
	
	public void outputTransformationMetadata(String outputDir, FileSystem fs, TfUtils agents) throws IOException {
		
		try{
			if (_colList != null)
				for(int i=0; i < _colList.length; i++) {
					int colID = _colList[i];
					
					double imputedValue = Double.NaN;
					KahanObject gmean = null;
					if ( _mvMethodList[i] == MVMethod.GLOBAL_MEAN ) 
					{
						gmean = _meanList[i];
						imputedValue = _meanList[i]._sum;
						
						double mean = ( _countList[i] == 0 ? 0.0 : _meanList[i]._sum); 
						writeTfMtd(colID, Double.toString(mean), outputDir, fs, agents);
					}
					else if ( _mvMethodList[i] == MVMethod.CONSTANT ) 
					{
						writeTfMtd(colID, _replacementList[i], outputDir, fs, agents);
						
						if (_isMVScaled.get(i) )
						{
							imputedValue = UtilFunctions.parseToDouble(_replacementList[i]);
							// adjust the global mean, by combining gmean with "replacement" (weight = #missing values)
							gmean = new KahanObject(_meanList[i]._sum, _meanList[i]._correction);
							_meanFn.execute(gmean, imputedValue, agents.getValid());
						}
					}
						
					if ( _isMVScaled.get(i) ) 
					{
						double sdev = -1.0;
						if ( _mvscMethodList[i] == MVMethod.GLOBAL_MODE ) {
							// Adjust variance with missing values
							long totalMissingCount = (agents.getValid() - _countList[i]);
							_varFn.execute(_varList[i], imputedValue, totalMissingCount);
							double var = _varList[i].getRequiredResult(new CMOperator(_varFn, AggregateOperationTypes.VARIANCE));
							sdev = Math.sqrt(var);
						}
						writeTfMtd(colID, Double.toString(gmean._sum), Double.toString(sdev), outputDir, fs, agents);
					}
				}
		
			if(_scnomvList != null)
				for(int i=0; i < _scnomvList.length; i++ )
				{
					int colID = _scnomvList[i];
					double mean = (_scnomvCountList[i] == 0 ? 0.0 : _scnomvMeanList[i]._sum);
					double sdev = -1.0;
					if ( _scnomvMethodList[i] == MVMethod.GLOBAL_MODE ) 
					{
						double var = _scnomvVarList[i].getRequiredResult(new CMOperator(_varFn, AggregateOperationTypes.VARIANCE));
						sdev = Math.sqrt(var);
					}
					writeTfMtd(colID, Double.toString(mean), Double.toString(sdev), outputDir, fs, agents);
				}
			
		} catch(DMLRuntimeException e) {
			throw new IOException(e); 
		}
	}
	
	/** 
	 * Method to merge map output transformation metadata. 
	 */
	@Override
	public void mergeAndOutputTransformationMetadata(Iterator<DistinctValue> values, String outputDir, int colID, FileSystem fs, TfUtils agents) throws IOException {
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		int nbins = 0;
		double d;
		long totalRecordCount = 0, totalValidCount=0;
		String mvConstReplacement = null;
		
		DistinctValue val = new DistinctValue();
		String w = null;
		
		class MeanObject {
			double mean, correction;
			long count;
			
			MeanObject() { }
			public String toString() {
				return mean + "," + correction + "," + count;
			}
		};
		HashMap<Integer, MeanObject> mapMeans = new HashMap<Integer, MeanObject>();
		HashMap<Integer, CM_COV_Object> mapVars = new HashMap<Integer, CM_COV_Object>();
		boolean isImputed = false;
		boolean isScaled = false;
		boolean isBinned = false;
		
		while(values.hasNext()) {
			val.reset();
			val = values.next();
			w = val.getWord();
			
			if(w.startsWith(MEAN_PREFIX)) {
				String[] parts = w.split("_");
				int taskID = UtilFunctions.parseToInt(parts[1]);
				MeanObject mo = mapMeans.get(taskID);
				if ( mo==null ) 
					mo = new MeanObject();
				
				mo.mean = UtilFunctions.parseToDouble(parts[2].split(",")[0]);
				
				// check if this attribute is scaled
				String s = parts[2].split(",")[1]; 
				if(s.equalsIgnoreCase("scmv"))
					isScaled = isImputed = true;
				else if ( s.equalsIgnoreCase("scnomv") )
					isScaled = true;
				else
					isImputed = true;
				
				mapMeans.put(taskID, mo);
			}
			else if (w.startsWith(CORRECTION_PREFIX)) {
				String[] parts = w.split("_");
				int taskID = UtilFunctions.parseToInt(parts[1]);
				MeanObject mo = mapMeans.get(taskID);
				if ( mo==null ) 
					mo = new MeanObject();
				mo.correction = UtilFunctions.parseToDouble(parts[2]);
				mapMeans.put(taskID, mo);
			}
			else if ( w.startsWith(CONSTANT_PREFIX) )
			{
				isImputed = true;
				String[] parts = w.split("_");
				mvConstReplacement = parts[1];
			}
			else if (w.startsWith(COUNT_PREFIX)) {
				String[] parts = w.split("_");
				int taskID = UtilFunctions.parseToInt(parts[1]);
				MeanObject mo = mapMeans.get(taskID);
				if ( mo==null ) 
					mo = new MeanObject();
				mo.count = UtilFunctions.parseToLong(parts[2]);
				totalValidCount += mo.count;
				mapMeans.put(taskID, mo);
			}
			else if (w.startsWith(TOTAL_COUNT_PREFIX)) {
				String[] parts = w.split("_");
				//int taskID = UtilFunctions.parseToInt(parts[1]);
				totalRecordCount += UtilFunctions.parseToLong(parts[2]);
			}
			else if (w.startsWith(VARIANCE_PREFIX)) {
				isScaled = true;
				String[] parts = w.split("_");
				int taskID = UtilFunctions.parseToInt(parts[1]);
				CM_COV_Object cm = decodeCMObj(parts[2]);
				mapVars.put(taskID, cm);
			}
			else if(w.startsWith(BinAgent.MIN_PREFIX)) {
				isBinned = true;
				d = UtilFunctions.parseToDouble( w.substring( BinAgent.MIN_PREFIX.length() ) );
				if ( d < min )
					min = d;
			}
			else if(w.startsWith(BinAgent.MAX_PREFIX)) {
				isBinned = true;
				d = UtilFunctions.parseToDouble( w.substring( BinAgent.MAX_PREFIX.length() ) );
				if ( d > max )
					max = d;
			}
			else if (w.startsWith(BinAgent.NBINS_PREFIX)) {
				isBinned = true;
				nbins = (int) UtilFunctions.parseToLong( w.substring(BinAgent.NBINS_PREFIX.length() ) );
			}
			else
				throw new RuntimeException("MVImputeAgent: Invalid prefix while merging map output: " + w);
		}
		
		// compute global mean across all map outputs
		KahanObject gmean = new KahanObject(0, 0);
		KahanPlus kp = KahanPlus.getKahanPlusFnObject();
		long gcount = 0;
		for(MeanObject mo : mapMeans.values()) {
			gcount = gcount + mo.count;
			if ( gcount > 0) {
				double delta = mo.mean - gmean._sum;
				kp.execute2(gmean, delta*mo.count/gcount);
				//_meanFn.execute2(gmean, mo.mean*mo.count, gcount);
			}
		}
		
		// compute global variance across all map outputs
		CM_COV_Object gcm = new CM_COV_Object();
		try {
			for(CM_COV_Object cm : mapVars.values())
				gcm = (CM_COV_Object) _varFn.execute(gcm, cm);
		} catch (DMLRuntimeException e) {
			throw new IOException(e);
		}
		
		// If the column is imputed with a constant, then adjust min and max based the value of the constant.
		if(isImputed && isBinned && mvConstReplacement != null)
		{
			double cst = UtilFunctions.parseToDouble(mvConstReplacement);
			if ( cst < min)
				min = cst;
			if ( cst > max)
				max = cst;
		}

		// write merged metadata
		if( isImputed ) 
		{
			String imputedValue = null;
			if ( mvConstReplacement != null )
				imputedValue = mvConstReplacement;
			else 
				imputedValue = Double.toString(gcount == 0 ? 0.0 : gmean._sum);
			
			writeTfMtd(colID, imputedValue, outputDir, fs, agents);
		}
		
		if ( isBinned ) {
			double binwidth = (max-min)/nbins;
			writeTfMtd(colID, Double.toString(min), Double.toString(max), Double.toString(binwidth), Integer.toString(nbins), outputDir, fs, agents);
		}
		
		if ( isScaled ) 
		{
			try {
				if( totalValidCount != totalRecordCount) {
					// In the presence of missing values, the variance needs to be adjusted.
					// The mean does not need to be adjusted, when mv impute method is global_mean, 
					// since missing values themselves are replaced with gmean.
					long totalMissingCount = (totalRecordCount-totalValidCount);
					int idx = isApplicable(colID);
					if(idx != -1 && _mvMethodList[idx] == MVMethod.CONSTANT) 
						_meanFn.execute(gmean, UtilFunctions.parseToDouble(_replacementList[idx]), totalRecordCount);
					_varFn.execute(gcm, gmean._sum, totalMissingCount);
				}
				
				double mean = (gcount == 0 ? 0.0 : gmean._sum);
				double var = gcm.getRequiredResult(new CMOperator(_varFn, AggregateOperationTypes.VARIANCE));
				double sdev = (mapVars.size() > 0 ? Math.sqrt(var) : -1.0 );
				
				writeTfMtd(colID, Double.toString(mean), Double.toString(sdev), outputDir, fs, agents);
				
				
			} catch (DMLRuntimeException e) {
				throw new IOException(e);
			}
		}
	}
	
	// ------------------------------------------------------------------------------------------------

	private String readReplacement(int colID, FileSystem fs, Path  txMtdDir, TfUtils agents) throws IOException
	{
		Path path = new Path( txMtdDir + "/Impute/" + agents.getName(colID) + TfUtils.TXMTD_MV_FILE_SUFFIX);
		TfUtils.checkValidInputFile(fs, path, true); 
		
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(path)));
		String line = br.readLine();
		String replacement =  UtilFunctions.unquote(line.split(TfUtils.TXMTD_SEP)[1]);
		br.close();
		
		return replacement;
	}
	
	public String readScaleLine(int colID, FileSystem fs, Path txMtdDir, TfUtils agents) throws IOException
	{
		Path path = new Path( txMtdDir + "/Scale/" + agents.getName(colID) + TfUtils.SCALE_FILE_SUFFIX);
		TfUtils.checkValidInputFile(fs, path, true); 
		BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(path)));
		String line = br.readLine();
		br.close();
		
		return line;
	}
	
	private void processScalingFile(int i, int[] list, KahanObject[] meanList, CM_COV_Object[] varList, FileSystem fs, Path tfMtdDir, TfUtils agents ) throws IOException
	{
		int colID = list[i];
		
		String line = readScaleLine(colID, fs, tfMtdDir, agents);
		String[] parts = line.split(",");
		double mean = UtilFunctions.parseToDouble(parts[1]);
		double sd = UtilFunctions.parseToDouble(parts[2]);
		
		meanList[i]._sum = mean;
		varList[i].mean._sum = sd;
	}
	
	// ------------------------------------------------------------------------------------------------

	/**
	 * Method to load transform metadata for all attributes
	 */
	@Override
	public void loadTxMtd(JobConf job, FileSystem fs, Path tfMtdDir, TfUtils agents) throws IOException {
		
		if(fs.isDirectory(tfMtdDir)) {
			
			// Load information about missing value imputation
			if (_colList != null)
				for(int i=0; i<_colList.length;i++) {
					int colID = _colList[i];
					
					if ( _mvMethodList[i] == MVMethod.GLOBAL_MEAN || _mvMethodList[i] == MVMethod.GLOBAL_MODE )
						// global_mean or global_mode
						_replacementList[i] = readReplacement(colID, fs, tfMtdDir, agents);
					else if ( _mvMethodList[i] == MVMethod.CONSTANT ) {
						// constant: replace a missing value by a given constant
						// nothing to do. The constant values are loaded already during configure 
					}
					else
						throw new RuntimeException("Invalid Missing Value Imputation methods: " + _mvMethodList[i]);
				}
			
			// Load scaling information
			if(_colList != null)
				for(int i=0; i < _colList.length; i++)
					if ( _isMVScaled.get(i) ) 
						processScalingFile(i, _colList, _meanList, _varList, fs, tfMtdDir, agents);
			
			if(_scnomvList != null)
				for(int i=0; i < _scnomvList.length; i++)
					processScalingFile(i, _scnomvList, _scnomvMeanList, _scnomvVarList, fs, tfMtdDir, agents);
		}
		else {
			fs.close();
			throw new RuntimeException("Path to recode maps must be a directory: " + tfMtdDir);
		}
	}
	
	public MVMethod getMethod(int colID) {
		int idx = isApplicable(colID);		
		if(idx == -1)
			return MVMethod.INVALID;
		else
			return _mvMethodList[idx];
	}
	
	public long getNonMVCount(int colID) {
		int idx = isApplicable(colID);
		return (idx == -1) ? 0 : _countList[idx];
	}
	
	public String getReplacement(int colID)  {
		int idx = isApplicable(colID);		
		return (idx == -1) ? null : _replacementList[idx];
	}
	
	@Override
	public MatrixBlock encode(FrameBlock in, MatrixBlock out) {
		build(in);
		return apply(in, out);
	}
	
	@Override
	public void build(FrameBlock in) {
		try {
			for( int j=0; j<_colList.length; j++ ) {
				int colID = _colList[j];
				if( _mvMethodList[j] == MVMethod.GLOBAL_MEAN ) {
					//compute global column mean (scale)
					long off = _countList[j];
					for( int i=0; i<in.getNumRows(); i++ )
						_meanFn.execute2(_meanList[j], UtilFunctions.objectToDouble(
							in.getSchema()[colID-1], in.get(i, colID-1)), off+i+1);
					_replacementList[j] = String.valueOf(_meanList[j]._sum);
					_countList[j] += in.getNumRows();
				}
				else if( _mvMethodList[j] == MVMethod.GLOBAL_MODE ) {
					//compute global column mode (categorical), i.e., most frequent category
					HashMap<String,Long> hist = _hist.containsKey(colID) ? 
							_hist.get(colID) : new HashMap<String,Long>();
					for( int i=0; i<in.getNumRows(); i++ ) {
						String key = String.valueOf(in.get(i, colID-1));
						if( key != null && !key.isEmpty() ) {
							Long val = hist.get(key);
							hist.put(key, (val!=null) ? val+1 : 1);
						}	
					}
					_hist.put(colID, hist);
					long max = Long.MIN_VALUE; 
					for( Entry<String, Long> e : hist.entrySet() ) 
						if( e.getValue() > max  ) {
							_replacementList[j] = e.getKey();
							max = e.getValue();
						}
				}
			}
		}
		catch(Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	@Override
	public String[] apply(String[] words) 
	{	
		if( isApplicable() )
			for(int i=0; i < _colList.length; i++) {
				int colID = _colList[i];
				String w = UtilFunctions.unquote(words[colID-1]);
				if(TfUtils.isNA(_NAstrings, w))
					w = words[colID-1] = _replacementList[i];
				
				if ( _isMVScaled.get(i) )
					if ( _mvscMethodList[i] == MVMethod.GLOBAL_MEAN )
						words[colID-1] = Double.toString( UtilFunctions.parseToDouble(w) - _meanList[i]._sum );
					else
						words[colID-1] = Double.toString( (UtilFunctions.parseToDouble(w) - _meanList[i]._sum) / _varList[i].mean._sum );
			}
		
		if(_scnomvList != null)
		for(int i=0; i < _scnomvList.length; i++)
		{
			int colID = _scnomvList[i];
			if ( _scnomvMethodList[i] == MVMethod.GLOBAL_MEAN )
				words[colID-1] = Double.toString( UtilFunctions.parseToDouble(words[colID-1]) - _scnomvMeanList[i]._sum );
			else
				words[colID-1] = Double.toString( (UtilFunctions.parseToDouble(words[colID-1]) - _scnomvMeanList[i]._sum) / _scnomvVarList[i].mean._sum );
		}
			
		return words;
	}
	
	@Override
	public MatrixBlock apply(FrameBlock in, MatrixBlock out) {
		for(int i=0; i<in.getNumRows(); i++) {
			for(int j=0; j<_colList.length; j++) {
				int colID = _colList[j];
				if( Double.isNaN(out.quickGetValue(i, colID-1)) )
					out.quickSetValue(i, colID-1, Double.parseDouble(_replacementList[j]));
			}
		}
		return out;
	}
	
	@Override
	public FrameBlock getMetaData(FrameBlock out) {
		for( int j=0; j<_colList.length; j++ ) {
			out.getColumnMetadata(_colList[j]-1)
			   .setMvValue(_replacementList[j]);
		}
		return out;
	}

	public void initMetaData(FrameBlock meta) {
		//init replacement lists, replace recoded values to
		//apply mv imputation potentially after recoding
		for( int j=0; j<_colList.length; j++ ) {
			int colID = _colList[j];	
			String mvVal = UtilFunctions.unquote(meta.getColumnMetadata(colID-1).getMvValue()); 
			if( _rcList.contains(colID) ) {
				Long mvVal2 = meta.getRecodeMap(colID-1).get(mvVal);
				if( mvVal2 == null)
					throw new RuntimeException("Missing recode value for impute value '"+mvVal+"' (colID="+colID+").");
				_replacementList[j] = mvVal2.toString();
			}
			else {
				_replacementList[j] = mvVal;
			}
		}
	}

	public void initRecodeIDList(List<Integer> rcList) {
		_rcList = rcList;
	}
	
	/**
	 * Exposes the internal histogram after build.
	 * 
	 * @param colID column ID
	 * @return histogram (map of string keys and long values)
	 */
	public HashMap<String,Long> getHistogram( int colID ) {
		return _hist.get(colID);
	}
}
