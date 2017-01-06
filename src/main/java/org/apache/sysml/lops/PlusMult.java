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

package org.apache.sysml.lops;

import org.apache.sysml.hops.Hop.OpOp3;
import org.apache.sysml.lops.LopProperties.ExecLocation;
import org.apache.sysml.lops.LopProperties.ExecType;
import org.apache.sysml.lops.compile.JobType;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;


/**
 * Lop to perform Sum of a matrix with another matrix multiplied by Scalar.
 */
public class PlusMult extends Lop 
{
	
	private void init(Lop input1, Lop input2, Lop input3, ExecType et) {
		addInput(input1);
		addInput(input2);
		addInput(input3);
		input1.addOutput(this);	
		input2.addOutput(this);	
		input3.addOutput(this);	
		
		boolean breaksAlignment = false;
		boolean aligner = false;
		boolean definesMRJob = false;
		
		if ( et == ExecType.CP ||  et == ExecType.SPARK || et == ExecType.GPU ){
			lps.addCompatibility(JobType.INVALID);
			lps.setProperties( inputs, et, ExecLocation.ControlProgram, breaksAlignment, aligner, definesMRJob );
		}
		else if( et == ExecType.MR ) {
			lps.addCompatibility(JobType.GMR);
			lps.addCompatibility(JobType.DATAGEN);
			lps.addCompatibility(JobType.REBLOCK);
			lps.setProperties( inputs, et, ExecLocation.Reduce, breaksAlignment, aligner, definesMRJob );
		}
	}
	
	public PlusMult(Lop input1, Lop input2, Lop input3, OpOp3 op, DataType dt, ValueType vt, ExecType et) {
		super(Lop.Type.PlusMult, dt, vt);
		if(op == OpOp3.MINUS_MULT)
			type=Lop.Type.MinusMult;
		init(input1, input2, input3, et);
	}

	@Override
	public String toString() {
		return "Operation = PlusMult";
	}
	
	public String getOpString() {
		return (type==Lop.Type.PlusMult) ? "+*" : "-*";
	}
	
	/**
	 * Function to generate CP/Spark axpy.
	 * 
	 * input1: matrix1
	 * input2: Scalar
	 * input3: matrix2
	 */
	@Override
	public String getInstructions(String input1, String input2, String input3, String output) {
		StringBuilder sb = new StringBuilder();
		
		sb.append( getExecType() );
		sb.append( OPERAND_DELIMITOR );
		
		sb.append(getOpString());
		sb.append( OPERAND_DELIMITOR );
		
		// Matrix1
		sb.append( getInputs().get(0).prepInputOperand(input1) );
		sb.append( OPERAND_DELIMITOR );
		
		// Scalar
		sb.append( getInputs().get(1).prepScalarInputOperand(input2) );
		sb.append( OPERAND_DELIMITOR );
		
		// Matrix2
		sb.append( getInputs().get(2).prepInputOperand(input3));
		sb.append( OPERAND_DELIMITOR );
		
		sb.append( prepOutputOperand(output));
		
		return sb.toString();
	}
	
	@Override
	public String getInstructions(int input1, int input2, int input3, int output) 
		throws LopsException 
	{
		StringBuilder sb = new StringBuilder();
		
		sb.append( getExecType() );
		sb.append( OPERAND_DELIMITOR );
		
		sb.append(getOpString());
		sb.append( OPERAND_DELIMITOR );
		
		// Matrix1
		sb.append( getInputs().get(0).prepInputOperand(input1) );
		sb.append( OPERAND_DELIMITOR );
		
		// Scalar
		sb.append( getInputs().get(1).prepScalarLabel() );
		sb.append( OPERAND_DELIMITOR );
		
		// Matrix2
		sb.append( getInputs().get(2).prepInputOperand(input3));
		sb.append( OPERAND_DELIMITOR );
		
		sb.append( prepOutputOperand(output));
		
		return sb.toString();
	}
}