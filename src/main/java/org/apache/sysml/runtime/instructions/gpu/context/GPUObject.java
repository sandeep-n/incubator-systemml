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
package org.apache.sysml.runtime.instructions.gpu.context;

import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.caching.CacheException;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.utils.Statistics;

//FIXME merge JCudaObject into GPUObject to avoid unnecessary complexity
public abstract class GPUObject 
{
	public enum EvictionPolicy {
        LRU, LFU, MIN_EVICT
    }
	public static final EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
	protected boolean isDeviceCopyModified = false;
	protected AtomicInteger numLocks = new AtomicInteger(0);
	AtomicLong timestamp = new AtomicLong(0);
	
	protected boolean isInSparseFormat = false;
	protected MatrixObject mat = null;
	
	protected GPUObject(MatrixObject mat2)  {
		this.mat = mat2;
	}
	
	public boolean isInSparseFormat() {
		return isInSparseFormat;
	}
	
	public abstract boolean isAllocated();
	
	public abstract void acquireDeviceRead() throws DMLRuntimeException;
	/**
	 * To signal intent that a matrix block will be written to on the GPU
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public abstract void acquireDeviceModifyDense() throws DMLRuntimeException;
	/**
	 * To signal intent that a sparse matrix block will be written to on the GPU
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	public abstract void acquireDeviceModifySparse() throws DMLRuntimeException;
	
	/**
	 * If memory on GPU has been allocated from elsewhere, this method 
	 * updates the internal bookkeeping
	 * @param numBytes number of bytes
	 */
	public abstract void setDeviceModify(long numBytes);
	
	public abstract void acquireHostRead() throws CacheException;
	public abstract void acquireHostModify() throws CacheException;
	public abstract void releaseInput() throws CacheException;
	public abstract void releaseOutput() throws CacheException;
	
	// package-level visibility as these methods are guarded by underlying GPUContext

	abstract void allocateDenseMatrixOnDevice() throws DMLRuntimeException;
	abstract void allocateSparseMatrixOnDevice() throws DMLRuntimeException;
	abstract void deallocateMemoryOnDevice() throws DMLRuntimeException;
	abstract long getSizeOnDevice() throws DMLRuntimeException;
	
	abstract void copyFromHostToDevice() throws DMLRuntimeException;
	
	/**
	 * Copies a matrix block (dense or sparse) from GPU Memory to Host memory.
	 * A {@link MatrixBlock} instance is allocated, data from the GPU is copied in,
	 * the current one in Host memory is deallocated by calling MatrixObject's acquireHostModify(MatrixBlock) (??? does not exist)
	 * and overwritten with the newly allocated instance.
	 * TODO : re-examine this to avoid spurious allocations of memory for optimizations
	 * @throws DMLRuntimeException if DMLRuntimeException occurs
	 */
	abstract void copyFromDeviceToHost() throws DMLRuntimeException; // Called by export()
	
	/**
	 * Cycles through the sorted list of allocated {@link GPUObject} instances. Sorting is based on
	 * number of (read) locks that have been obtained on it (reverse order). It repeatedly frees up 
	 * blocks on which there are zero locks until the required size has been freed up.  
	 * // TODO: update it with hybrid policy
	 * @param GPUSize Desired size to be freed up on the GPU
	 * @throws DMLRuntimeException If no blocks to free up or if not enough blocks with zero locks on them.	 
	 */
	protected static void evict(final long GPUSize) throws DMLRuntimeException {
        if(GPUContext.allocatedPointers.size() == 0) {
                throw new DMLRuntimeException("There is not enough memory on device for this matrix!");
        }
        
        Statistics.cudaEvictionCount.addAndGet(1);

        synchronized(evictionLock) {
        	Collections.sort(GPUContext.allocatedPointers, new Comparator<GPUObject>() {

        		@Override
                public int compare(GPUObject p1, GPUObject p2) {
                	long p1Val = p1.numLocks.get();
                 	long p2Val = p2.numLocks.get();

                	if(p1Val>0 && p2Val>0) {
                		// Both are locked, so don't sort
                        return 0;
                	}
                	else if(p1Val>0 || p2Val>0) {
                		// Put the unlocked one to RHS
                		return Long.compare(p2Val, p1Val);
                    }
                	else {
                		// Both are unlocked

                		if(evictionPolicy == EvictionPolicy.MIN_EVICT) {
                			long p1Size = 0; long p2Size = 0;
                          	try {
                          		p1Size = p1.getSizeOnDevice() - GPUSize;
                            	p2Size = p2.getSizeOnDevice() - GPUSize;
                         	} catch (DMLRuntimeException e) {
                         		throw new RuntimeException(e);
                        	}

                          	if(p1Size>=0 && p2Size>=0 ) {
                          		return Long.compare(p2Size, p1Size);
                          	}
                          	else {
                          		return Long.compare(p1Size, p2Size);
                          	}
                     	}
                		else if(evictionPolicy == EvictionPolicy.LRU || evictionPolicy == EvictionPolicy.LFU) {
                			return Long.compare(p2.timestamp.get(), p1.timestamp.get());
                    	}
                     	else {
                     		throw new RuntimeException("Unsupported eviction policy:" + evictionPolicy.name());
                    	}
                	}
              	}
        	});

        	while(GPUSize > getAvailableMemory() && GPUContext.allocatedPointers.size() > 0) {
        		GPUObject toBeRemoved = GPUContext.allocatedPointers.get(GPUContext.allocatedPointers.size() - 1);
               	if(toBeRemoved.numLocks.get() > 0) {
               		throw new DMLRuntimeException("There is not enough memory on device for this matrix!");
              	}
               	if(toBeRemoved.isDeviceCopyModified) {
               		toBeRemoved.copyFromDeviceToHost();
            	}
             	toBeRemoved.clearData();
        	}
        }
	}
	
	public void clearData() throws CacheException {
		synchronized(evictionLock) {
			GPUContext.allocatedPointers.remove(this);
		}
		try {
			deallocateMemoryOnDevice();
		} catch (DMLRuntimeException e) {
			throw new CacheException(e);
		}
	}
	
	static Boolean evictionLock = new Boolean(true);
	
	protected static long getAvailableMemory() {
		return GPUContext.currContext.getAvailableMemory();
	}
	
//	// Copying from device -> host occurs here
//	// Called by MatrixObject's exportData
//	public void exportData() throws CacheException {
//		boolean isDeviceCopyModified = mat.getGPUObject() != null && mat.getGPUObject().isDeviceCopyModified;
//		boolean isHostCopyUnavailable = mat.getMatrixBlock() == null || 
//				(mat.getMatrixBlock().getDenseBlock() == null && mat.getMatrixBlock().getSparseBlock() == null);
//		
//		if(mat.getGPUObject() != null && (isDeviceCopyModified || isHostCopyUnavailable)) {
//			try {
//				mat.getGPUObject().copyFromDeviceToHost();
//			} catch (DMLRuntimeException e) {
//				throw new CacheException(e);
//			}
//		}
//	}
}
