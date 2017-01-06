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

package org.apache.sysml.hops.cost;

public class VarStats 
{	

	
	long _rlen = -1;
	long _clen = -1;
	long _brlen = -1;
	long _bclen = -1;
	double _nnz = -1;
	boolean _inmem = false;
	
	public VarStats( long rlen, long clen, long brlen, long bclen, long nnz, boolean inmem )
	{
		_rlen = rlen;
		_clen = clen;
		_brlen = brlen;
		_bclen = bclen;
		_nnz = nnz;
		_inmem = inmem;
	}
	
	public double getSparsity()
	{
		return (_nnz<0) ? 1.0 : (double)_nnz/_rlen/_clen;
	}
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("VarStats: [");
		sb.append("rlen = ");
		sb.append(_rlen);
		sb.append(", clen = ");
		sb.append(_clen);
		sb.append(", nnz = ");
		sb.append(_nnz);
		sb.append(", inmem = ");
		sb.append(_inmem);
		sb.append("]");
	
		return sb.toString();
	}
	
	@Override
	public Object clone()
	{
		return new VarStats(_rlen, _clen, _brlen, _bclen,(long)_nnz, _inmem );
	}
}
