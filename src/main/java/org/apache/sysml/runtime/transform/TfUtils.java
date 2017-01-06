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

import java.io.EOFException;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.Reader;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.parser.DataExpression;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.apache.sysml.runtime.io.MatrixReader;
import org.apache.sysml.runtime.matrix.CSVReblockMR;
import org.apache.sysml.runtime.matrix.CSVReblockMR.OffsetCount;
import org.apache.sysml.runtime.matrix.mapred.MRConfigurationNames;
import org.apache.sysml.runtime.matrix.mapred.MRJobConfiguration;
import org.apache.sysml.runtime.util.MapReduceTool;
import org.apache.sysml.runtime.util.UtilFunctions;

@SuppressWarnings("deprecation")
public class TfUtils implements Serializable{
	
	private static final long serialVersionUID = 526252850872633125L;

	protected enum ColumnTypes { 
		SCALE,
		NOMINAL,
		ORDINAL,
		DUMMYCODED;
	
		protected byte toID() { 
			switch(this) {
				case SCALE: return 1;
				case NOMINAL: return 2;
				case ORDINAL: return 3;
				// Ideally, dummycoded columns should be of a different type. Treating them as SCALE is incorrect, semantically.
				case DUMMYCODED: return 1; 
				default:
					throw new RuntimeException("Invalid Column Type: " + this);
			}
		}	
	}
	
	//transform methods
	public static final String TXMETHOD_IMPUTE    = "impute";
	public static final String TXMETHOD_RECODE    = "recode";
	public static final String TXMETHOD_BIN       = "bin";
	public static final String TXMETHOD_DUMMYCODE = "dummycode";
	public static final String TXMETHOD_SCALE     = "scale";
	public static final String TXMETHOD_OMIT      = "omit";
	public static final String TXMETHOD_MVRCD     = "mvrcd";
		
	//transform meta data constants (frame-based transform)
	public static final String TXMTD_MVPREFIX = "#Meta"+Lop.DATATYPE_PREFIX+"MV";
	public static final String TXMTD_NDPREFIX = "#Meta"+Lop.DATATYPE_PREFIX+"ND";
	
	//transform meta data constants (old file-based transform)
	public static final String TXMTD_SEP         = ",";
	public static final String TXMTD_COLTYPES    = "coltypes.csv";	
	public static final String TXMTD_COLNAMES    = "column.names";
	public static final String TXMTD_DC_COLNAMES = "dummycoded.column.names";	
	public static final String TXMTD_RCD_MAP_SUFFIX      = ".map";
	public static final String TXMTD_RCD_DISTINCT_SUFFIX = ".ndistinct";
	public static final String TXMTD_BIN_FILE_SUFFIX     = ".bin";
	public static final String TXMTD_MV_FILE_SUFFIX      = ".impute";
	
	public static final String JSON_ATTRS 	= "attributes"; 
	public static final String JSON_MTHD 	= "methods"; 
	public static final String JSON_CONSTS = "constants"; 
	public static final String JSON_NBINS 	= "numbins"; 		
	protected static final String MODE_FILE_SUFFIX 		= ".mode";
	protected static final String SCALE_FILE_SUFFIX		= ".scale";
	protected static final String DCD_FILE_NAME 		= "dummyCodeMaps.csv";	
	protected static final String DCD_NAME_SEP 	= "_";
	
	
	private OmitAgent _oa = null;
	private MVImputeAgent _mia = null;
	private RecodeAgent _ra = null;	
	private BinAgent _ba = null;
	private DummycodeAgent _da = null;
	
	private long _numRecordsInPartFile;		// Total number of records in the data file
	private long _numValidRecords;			// (_numRecordsInPartFile - #of omitted records)
	private long _numTransformedRows; 		// Number of rows after applying transformations
	private long _numTransformedColumns; 	// Number of columns after applying transformations

	private String _headerLine = null;
	private boolean _hasHeader;
	private Pattern _delim = null;
	private String _delimString = null;
	private String[] _NAstrings = null;
	private String[] _outputColumnNames = null;
	private int _numInputCols = -1;
	
	private String _tfMtdDir = null;
	private String _spec = null;
	private String _offsetFile = null;
	private String _tmpDir = null;
	private String _outputPath = null;
	
	public TfUtils(JobConf job, boolean minimal) 
		throws IOException, JSONException 
	{
		if( !InfrastructureAnalyzer.isLocalMode(job) ) {
			ConfigurationManager.setCachedJobConf(job);
		}		
		_NAstrings = TfUtils.parseNAStrings(job);
		_spec = job.get(MRJobConfiguration.TF_SPEC);
		_oa = new OmitAgent(new JSONObject(_spec), null, -1);
	}
	
	// called from GenTFMtdMapper, ApplyTf (Hadoop)
	public TfUtils(JobConf job) 
		throws IOException, JSONException 
	{
		if( !InfrastructureAnalyzer.isLocalMode(job) ) {
			ConfigurationManager.setCachedJobConf(job);
		}
		
		boolean hasHeader = Boolean.parseBoolean(job.get(MRJobConfiguration.TF_HAS_HEADER));
		String[] naStrings = TfUtils.parseNAStrings(job);
		long numCols = UtilFunctions.parseToLong( job.get(MRJobConfiguration.TF_NUM_COLS) ); // #cols input data
		String spec = job.get(MRJobConfiguration.TF_SPEC);
		String offsetFile = job.get(MRJobConfiguration.TF_OFFSETS_FILE);
		String tmpPath = job.get(MRJobConfiguration.TF_TMP_LOC);
		String outputPath = FileOutputFormat.getOutputPath(job).toString();
		JSONObject jspec = new JSONObject(spec);
		
		init(job.get(MRJobConfiguration.TF_HEADER), hasHeader, job.get(MRJobConfiguration.TF_DELIM), naStrings, jspec, numCols, offsetFile, tmpPath, outputPath);
	}
	
	// called from GenTfMtdReducer 
	public TfUtils(JobConf job, String tfMtdDir) throws IOException, JSONException 
	{
		this(job);
		_tfMtdDir = tfMtdDir;
	}
	
	// called from GenTFMtdReducer and ApplyTf (Spark)
	public TfUtils(String headerLine, boolean hasHeader, String delim, String[] naStrings, JSONObject spec, long ncol, String tfMtdDir, String offsetFile, String tmpPath) throws IOException, JSONException {
		init (headerLine, hasHeader, delim, naStrings, spec, ncol, offsetFile, tmpPath, null);
		_tfMtdDir = tfMtdDir;
	}

	protected static boolean checkValidInputFile(FileSystem fs, Path path, boolean err)
			throws IOException {
		// check non-existing file
		if (!fs.exists(path))
			if ( err )
				throw new IOException("File " + path.toString() + " does not exist on HDFS/LFS.");
			else
				return false;

		// check for empty file
		if (MapReduceTool.isFileEmpty(fs, path.toString()))
			if ( err )
			throw new EOFException("Empty input file " + path.toString() + ".");
			else
				return false;
		
		return true;
	}
	
	public static String getPartFileName(JobConf job) throws IOException {
		FileSystem fs = FileSystem.get(job);
		Path thisPath=new Path(job.get(MRConfigurationNames.MR_MAP_INPUT_FILE)).makeQualified(fs);
		return thisPath.toString();
	}
	
	public static boolean isPartFileWithHeader(JobConf job) throws IOException {
		FileSystem fs = FileSystem.get(job);
		
		String thisfile=getPartFileName(job);
		Path smallestFilePath=new Path(job.get(MRJobConfiguration.TF_SMALLEST_FILE)).makeQualified(fs);
		
		if(thisfile.toString().equals(smallestFilePath.toString()))
			return true;
		else
			return false;
	}
	
	/**
	 * Prepare NA strings so that they can be sent to workers via JobConf.
	 * A "dummy" string is added at the end to handle the case of empty strings.
	 * @param na NA string
	 * @return NA string concatenated with NA string separator concatenated with "dummy"
	 */
	public static String prepNAStrings(String na) {
		return na  + DataExpression.DELIM_NA_STRING_SEP + "dummy";
	}
	
	public static String[] parseNAStrings(String na) 
	{
		if ( na == null )
			return null;
		
		String[] tmp = Pattern.compile(Pattern.quote(DataExpression.DELIM_NA_STRING_SEP)).split(na, -1);
		return tmp; //Arrays.copyOf(tmp, tmp.length-1);
	}
	
	public static String[] parseNAStrings(JobConf job) 
	{
		return parseNAStrings(job.get(MRJobConfiguration.TF_NA_STRINGS));
	}
	
	private void createAgents(JSONObject spec, String[] naStrings) 
		throws IOException, JSONException 
	{
		_oa = new OmitAgent(spec, _outputColumnNames, _numInputCols);
		_mia = new MVImputeAgent(spec, null, naStrings, _numInputCols);
		_ra = new RecodeAgent(spec, _outputColumnNames, _numInputCols);
		_ba = new BinAgent(spec, _outputColumnNames, _numInputCols);
		_da = new DummycodeAgent(spec, _outputColumnNames, _numInputCols);
	}
	
	private void parseColumnNames() {
		_outputColumnNames = _delim.split(_headerLine, -1);
		for(int i=0; i < _outputColumnNames.length; i++)
			_outputColumnNames[i] = UtilFunctions.unquote(_outputColumnNames[i]);
	}
	
	private void init(String headerLine, boolean hasHeader, String delim, String[] naStrings, JSONObject spec, long numCols, String offsetFile, String tmpPath, String outputPath) throws IOException, JSONException
	{
		_numRecordsInPartFile = 0;
		_numValidRecords = 0;
		_numTransformedRows = 0;
		_numTransformedColumns = 0;
		
		//TODO: fix hard-wired header propagation to meta data column names
		
		_headerLine = headerLine;
		_hasHeader = hasHeader;
		_delimString = delim;
		_delim = Pattern.compile(Pattern.quote(delim));
		_NAstrings = naStrings;
		_numInputCols = (int)numCols;
		_offsetFile = offsetFile;
		_tmpDir = tmpPath;
		_outputPath = outputPath;
		
		parseColumnNames();		
		createAgents(spec, naStrings);
	}
	
	public void incrValid() { _numValidRecords++; }
	public long getValid()  { return _numValidRecords; }
	public long getTotal()  { return _numRecordsInPartFile; }
	public long getNumTransformedRows() 	{ return _numTransformedRows; }
	public long getNumTransformedColumns() 	{ return _numTransformedColumns; }
	
	public String getHeader() 		{ return _headerLine; }
	public boolean hasHeader() 		{ return _hasHeader; }
	public String getDelimString() 	{ return _delimString; }
	public Pattern getDelim() 		{ return _delim; }
	public String[] getNAStrings() 	{ return _NAstrings; }
	public long getNumCols() 		{ return _numInputCols; }
	
	public String getSpec() 	{ return _spec; }
	public String getTfMtdDir() 	{ return _tfMtdDir; }
	public String getOffsetFile() 	{ return _offsetFile; }
	public String getTmpDir() 		{ return _tmpDir; }
	public String getOutputPath()	{ return _outputPath; }
	
	public String getName(int colID) { return _outputColumnNames[colID-1]; }
	
	public void setValid(long n) { _numValidRecords = n;}
	public void incrTotal() { _numRecordsInPartFile++; }
	public void setTotal(long n) { _numRecordsInPartFile = n;}
	
	public OmitAgent 	  getOmitAgent() 	{ 	return _oa; }
	public MVImputeAgent  getMVImputeAgent(){ 	return _mia;}
	public RecodeAgent 	  getRecodeAgent() 	{ 	return _ra; }
	public BinAgent 	  getBinAgent() 	{ 	return _ba; }
	public DummycodeAgent getDummycodeAgent() { return _da; }
	
	/**
	 * Function that checks if the given string is one of NA strings.
	 * 
	 * @param NAstrings array of NA strings
	 * @param w string to check
	 * @return true if w is a NAstring
	 */
	public static boolean isNA(String[] NAstrings, String w) {
		if(NAstrings == null)
			return false;
		
		for(String na : NAstrings) {
			if(w.equals(na))
				return true;
		}
		return false;
	}
	
	public String[] getWords(Text line) {
		return getWords(line.toString());
	}
	

	public String[] getWords(String line) {
		return getDelim().split(line.trim(), -1);
	}
	
	/**
	 * Process a given row to construct transformation metadata.
	 * 
	 * @param line string to break into words
	 * @return string array of words from the line
	 * @throws IOException if IOException occurs
	 */
	public String[] prepareTfMtd(String line) throws IOException {
		String[] words = getWords(line);
		if(!getOmitAgent().omit(words, this))
		{
			getMVImputeAgent().prepare(words);
			getRecodeAgent().prepare(words, this);
			getBinAgent().prepare(words, this);
			incrValid();
		}
		incrTotal();
		
		return words;
	}
	
	public void loadTfMetadata() throws IOException 
	{
		JobConf job = ConfigurationManager.getCachedJobConf();
		loadTfMetadata(job, false);
	}
	
	public void loadTfMetadata(JobConf job, boolean fromLocalFS) throws IOException
	{
		Path tfMtdDir = null; 
		FileSystem fs = null;
		
		if(fromLocalFS) {
			// metadata must be read from local file system (e.g., distributed cache in the case of Hadoop)
			tfMtdDir = (DistributedCache.getLocalCacheFiles(job))[0];
			fs = FileSystem.getLocal(job);
		}
		else {
			fs = FileSystem.get(job);
			tfMtdDir = new Path(getTfMtdDir());
		}
		
		// load transformation metadata 
		getMVImputeAgent().loadTxMtd(job, fs, tfMtdDir, this);
		getRecodeAgent().loadTxMtd(job, fs, tfMtdDir, this);
		getBinAgent().loadTxMtd(job, fs, tfMtdDir, this);
		
		// associate recode maps and bin definitions with dummycoding agent,
		// as recoded and binned columns are typically dummycoded
		getDummycodeAgent().setRecodeMaps( getRecodeAgent().getRecodeMaps() );
		getDummycodeAgent().setNumBins(getBinAgent().getColList(), getBinAgent().getNumBins());
		getDummycodeAgent().loadTxMtd(job, fs, tfMtdDir, this);

	}

	public String processHeaderLine() throws IOException 
	{
		//TODO: fix hard-wired header propagation to meta data column names
		
		FileSystem fs = FileSystem.get(ConfigurationManager.getCachedJobConf());
		String dcdHeader = getDummycodeAgent().constructDummycodedHeader(getHeader(), getDelim());
		getDummycodeAgent().genDcdMapsAndColTypes(fs, getTmpDir(), (int) getNumCols(), this);
		
		// write header information (before and after transformation) to temporary path
		// these files are copied into txMtdPath, once the ApplyTf job is complete.
		DataTransform.generateHeaderFiles(fs, getTmpDir(), getHeader(), dcdHeader);

		return dcdHeader;
		//_numTransformedColumns = getDelim().split(dcdHeader, -1).length; 
		//return _numTransformedColumns;
	}

	public boolean omit(String[] words) {
		if(getOmitAgent() == null)
			return false;
		return getOmitAgent().omit(words, this);
	}
	
	/**
	 * Function to apply transformation metadata on a given row.
	 * 
	 * @param words string array of words
	 * @return string array of transformed words
	 */
	public String[] apply( String[] words ) {
		words = getMVImputeAgent().apply(words);
		words = getRecodeAgent().apply(words);
		words = getBinAgent().apply(words);
		words = getDummycodeAgent().apply(words);		
		_numTransformedRows++;
		
		return words;
	}
	
	public void check(String []words) throws DMLRuntimeException 
	{
		boolean checkEmptyString = ( getNAStrings() != null );
		if ( checkEmptyString ) 
		{
			final String msg = "When na.strings are provided, empty string \"\" is considered as a missing value, and it must be imputed appropriately. Encountered an unhandled empty string in column ID: ";
			for(int i=0; i<words.length; i++) 
				if ( words[i] != null && words[i].equals(""))
					throw new DMLRuntimeException(msg + getDummycodeAgent().mapDcdColumnID(i+1));
		}
	}
	
	public String checkAndPrepOutputString(String []words) throws DMLRuntimeException {
		return checkAndPrepOutputString(words, new StringBuilder());
	}
	
	public String checkAndPrepOutputString(String []words, StringBuilder sb) throws DMLRuntimeException 
	{
		/*
		 * Check if empty strings ("") have to be handled.
		 * 
		 * Unless na.strings are provided, empty strings are (implicitly) considered as value zero.
		 * When na.strings are provided, then "" is considered a missing value indicator, and the 
		 * user is expected to provide an appropriate imputation method. Therefore, when na.strings 
		 * are provided, "" encountered in any column (after all transformations are applied) 
		 * denotes an erroneous condition.  
		 */
		boolean checkEmptyString = ( getNAStrings() != null ); //&& !MVImputeAgent.isNA("", TransformationAgent.NAstrings) ) {
		
		//StringBuilder sb = new StringBuilder();
		sb.setLength(0);
		int i =0;
		
		if ( checkEmptyString ) 
		{
			final String msg = "When na.strings are provided, empty string \"\" is considered as a missing value, and it must be imputed appropriately. Encountered an unhandled empty string in column ID: ";
			if ( words[0] != null ) 
				if ( words[0].equals("") )
					throw new DMLRuntimeException( msg + getDummycodeAgent().mapDcdColumnID(1));
				else 
					sb.append(words[0]);
			else
				sb.append("0");
			
			for(i=1; i<words.length; i++) 
			{
				sb.append(_delimString);
				
				if ( words[i] != null ) 
					if ( words[i].equals("") )
						throw new DMLRuntimeException(msg + getDummycodeAgent().mapDcdColumnID(i+1));
					else 
						sb.append(words[i]);
				else
					sb.append("0");
			}
		}
		else 
		{
			sb.append(words[0] != null ? words[0] : "0");
			for(i=1; i<words.length; i++) 
			{
				sb.append(_delimString);
				sb.append(words[i] != null ? words[i] : "0");
			}
		}
		
		return sb.toString();
	}

	private Reader initOffsetsReader(JobConf job) throws IOException 
	{
		Path path=new Path(job.get(CSVReblockMR.ROWID_FILE_NAME));
		FileSystem fs = FileSystem.get(job);
		Path[] files = MatrixReader.getSequenceFilePaths(fs, path);
		if ( files.length != 1 )
			throw new IOException("Expecting a single file under counters file: " + path.toString());
		
		Reader reader = new SequenceFile.Reader(fs, files[0], job);
		
		return reader;
	}
	
	/**
	 * Function to generate custom file names (transform-part-.....) for
	 * mappers' output for ApplyTfCSV job. The idea is to find the index 
	 * of (thisfile, fileoffset) in the list of all offsets from the 
	 * counters/offsets file, which was generated from either GenTfMtdMR
	 * or AssignRowIDMR job.
	 * 
	 * @param job job configuration
	 * @param offset file offset
	 * @return part file id (ie, 00001, 00002, etc)
	 * @throws IOException if IOException occurs
	 */
	public String getPartFileID(JobConf job, long offset) throws IOException
	{
		Reader reader = initOffsetsReader(job);
		
		ByteWritable key=new ByteWritable();
		OffsetCount value=new OffsetCount();
		String thisFile = TfUtils.getPartFileName(job);
		
		int id = 0;
		while (reader.next(key, value)) {
			if ( thisFile.equals(value.filename) && value.fileOffset == offset ) 
				break;
			id++;
		}
		reader.close();
		
		String sid = Integer.toString(id);
		char[] carr = new char[5-sid.length()];
		Arrays.fill(carr, '0');
		String ret = (new String(carr)).concat(sid);
		
		return ret;
	}
}
