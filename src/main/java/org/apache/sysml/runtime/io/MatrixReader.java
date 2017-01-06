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

package org.apache.sysml.runtime.io;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.SparseBlock;
import org.apache.sysml.runtime.matrix.data.SparseBlockMCSR;
import org.apache.sysml.runtime.util.MapReduceTool;

/**
 * Base class for all format-specific matrix readers. Every reader is required to implement the basic 
 * read functionality but might provide additional custom functionality. Any non-default parameters
 * (e.g., CSV read properties) should be passed into custom constructors. There is also a factory
 * for creating format-specific readers. 
 * 
 */
public abstract class MatrixReader 
{
	//internal configuration
	protected static final boolean AGGREGATE_BLOCK_NNZ = true;
	
	public abstract MatrixBlock readMatrixFromHDFS( String fname, long rlen, long clen, int brlen, int bclen, long estnnz )
		throws IOException, DMLRuntimeException;

	public static Path[] getSequenceFilePaths( FileSystem fs, Path file ) 
		throws IOException
	{
		Path[] ret = null;
		
		if( fs.isDirectory(file) )
		{
			LinkedList<Path> tmp = new LinkedList<Path>();
			FileStatus[] dStatus = fs.listStatus(file);
			for( FileStatus fdStatus : dStatus )
				if( !fdStatus.getPath().getName().startsWith("_") ) //skip internal files
					tmp.add(fdStatus.getPath());
			ret = tmp.toArray(new Path[0]);
		}
		else
		{
			ret = new Path[]{ file };
		}
		
		return ret;
	}
	
	/**
	 * NOTE: mallocDense controls if the output matrix blocks is fully allocated, this can be redundant
	 * if binary block read and single block. 
	 * 
	 * @param rlen number of rows
	 * @param clen number of columns
	 * @param bclen number of columns in a block
	 * @param brlen number of rows in a block
	 * @param estnnz estimated number of non-zeros
	 * @param mallocDense if true and not sparse, allocate dense block unsafe
	 * @param mallocSparse if true and sparse, allocate sparse rows block
	 * @return matrix block
	 * @throws IOException if IOException occurs
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	protected static MatrixBlock createOutputMatrixBlock( long rlen, long clen, int bclen, int brlen, long estnnz, boolean mallocDense, boolean mallocSparse ) 
		throws IOException, DMLRuntimeException
	{
		//check input dimension
		if( !OptimizerUtils.isValidCPDimensions(rlen, clen) )
			throw new DMLRuntimeException("Matrix dimensions too large for CP runtime: "+rlen+" x "+clen);
		
		//determine target representation (sparse/dense)
		boolean sparse = MatrixBlock.evalSparseFormatInMemory(rlen, clen, estnnz); 
		
		//prepare result matrix block
		MatrixBlock ret = new MatrixBlock((int)rlen, (int)clen, sparse, estnnz);
		if( !sparse && mallocDense )
			ret.allocateDenseBlockUnsafe((int)rlen, (int)clen);
		else if( sparse && mallocSparse  ) {
			ret.allocateSparseRowsBlock();
			SparseBlock sblock = ret.getSparseBlock();
			//create synchronization points for MCSR (start row per block row)
			if( sblock instanceof SparseBlockMCSR && clen > bclen      //multiple col blocks 
				&& clen > 0 && bclen > 0 && rlen > 0 && brlen > 0 ) {  //all dims known
				for( int i=0; i<rlen; i+=brlen )
					ret.getSparseBlock().allocate(i, Math.min((int)(estnnz/rlen),1), (int)clen);
			}
		}
		
		return ret;
	}

	protected static void checkValidInputFile(FileSystem fs, Path path) 
		throws IOException
	{
		//check non-existing file
		if( !fs.exists(path) )	
			throw new IOException("File "+path.toString()+" does not exist on HDFS/LFS.");
	
		//check for empty file
		if( MapReduceTool.isFileEmpty( fs, path.toString() ) )
			throw new EOFException("Empty input file "+ path.toString() +".");
		
	}

	protected static void sortSparseRowsParallel(MatrixBlock dest, long rlen, int k, ExecutorService pool) 
		throws InterruptedException, ExecutionException
	{
		//create sort tasks
		ArrayList<SortRowsTask> tasks = new ArrayList<SortRowsTask>();
		int blklen = (int)(Math.ceil((double)rlen/k));
		for( int i=0; i<k & i*blklen<rlen; i++ )
			tasks.add(new SortRowsTask(dest, i*blklen, Math.min((i+1)*blklen, (int)rlen)));
		
		//execute parallel sort and check for errors
		List<Future<Object>> rt2 = pool.invokeAll(tasks);
		for( Future<Object> task : rt2 )
			task.get(); //error handling
	}
	
	/**
	 * Utility task for sorting sparse rows as potentially required
	 * by different parallel readers.
	 */
	private static class SortRowsTask implements Callable<Object> 
	{
		private MatrixBlock _dest = null;
		private int _rl = -1;
		private int _ru = -1;
		
		public SortRowsTask(MatrixBlock dest, int rl, int ru) {
			_dest = dest;
			_rl = rl;
			_ru = ru;
		}

		@Override
		public Object call() throws Exception {
			SparseBlock sblock = _dest.getSparseBlock();
			if( sblock != null )
				for( int i=_rl; i<_ru; i++ )
					if( !sblock.isEmpty(i) )
						sblock.sort(i);
			return null;
		}
	}
}
