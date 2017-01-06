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

package org.apache.sysml.parser;

import java.util.HashMap;


public class BinaryExpression extends Expression 
{
	
	private Expression _left;
	private Expression _right;
	private BinaryOp _opcode;

	
	public Expression rewriteExpression(String prefix) throws LanguageException{
		
		
		BinaryExpression newExpr = new BinaryExpression(this._opcode,
				this.getFilename(), this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
		newExpr.setLeft(_left.rewriteExpression(prefix));
		newExpr.setRight(_right.rewriteExpression(prefix));
		return newExpr;
	}
	
	public BinaryExpression(BinaryOp bop) {
		_kind = Kind.BinaryOp;
		_opcode = bop;
		
		setFilename("MAIN SCRIPT");
		setBeginLine(0);
		setBeginColumn(0);
		setEndLine(0);
		setEndColumn(0);
	}
	
	public BinaryExpression(BinaryOp bop, String filename, int beginLine, int beginColumn, int endLine, int endColumn) {
		_kind = Kind.BinaryOp;
		_opcode = bop;
		
		setFilename(filename);
		setBeginLine(beginLine);
		setBeginColumn(beginColumn);
		setEndLine(endLine);
		setEndColumn(endColumn);
	}
	

	public BinaryOp getOpCode() {
		return _opcode;
	}

	public void setLeft(Expression l) {
		_left = l;
		
		// update script location information --> left expression is BEFORE in script
		if (_left != null){
			setFilename(_left.getFilename());
			setBeginLine(_left.getBeginLine());
			setBeginColumn(_left.getBeginColumn());
		}
		
	}

	public void setRight(Expression r) {
		_right = r;
		
		// update script location information --> right expression is AFTER in script
		if (_right != null){
			setFilename(_right.getFilename());
			setBeginLine(_right.getEndLine());
			setBeginColumn(_right.getEndColumn());
		}
	}

	public Expression getLeft() {
		return _left;
	}

	public Expression getRight() {
		return _right;
	}

	/**
	 * Validate parse tree : Process Binary Expression in an assignment
	 * statement
	 * 
	 * @throws LanguageException if LanguageException occurs
	 */
	@Override
	public void validateExpression(HashMap<String, DataIdentifier> ids, HashMap<String, ConstIdentifier> constVars, boolean conditional)
			throws LanguageException 
	{	
		//recursive validate
		if (_left instanceof FunctionCallIdentifier || _right instanceof FunctionCallIdentifier){
			raiseValidateError("user-defined function calls not supported in binary expressions", 
		            false, LanguageException.LanguageErrorCodes.UNSUPPORTED_EXPRESSION);
		}
			
		_left.validateExpression(ids, constVars, conditional);
		_right.validateExpression(ids, constVars, conditional);
		
		//constant propagation (precondition for more complex constant folding rewrite)
		if( _left instanceof DataIdentifier && constVars.containsKey(((DataIdentifier) _left).getName()) )
			_left = constVars.get(((DataIdentifier) _left).getName());
		if( _right instanceof DataIdentifier && constVars.containsKey(((DataIdentifier) _right).getName()) )
			_right = constVars.get(((DataIdentifier) _right).getName());
		
		
		String outputName = getTempName();
		DataIdentifier output = new DataIdentifier(outputName);
		output.setAllPositions(this.getFilename(), this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());		
		output.setDataType(computeDataType(this.getLeft(), this.getRight(), true));
		ValueType resultVT = computeValueType(this.getLeft(), this.getRight(), true);

		// Override the computed value type, if needed
		if (this.getOpCode() == Expression.BinaryOp.POW
				|| this.getOpCode() == Expression.BinaryOp.DIV) {
			resultVT = ValueType.DOUBLE;
		}

		output.setValueType(resultVT);

		checkAndSetDimensions(output, conditional);
		if (this.getOpCode() == Expression.BinaryOp.MATMULT) {
			if ((this.getLeft().getOutput().getDataType() != DataType.MATRIX) || (this.getRight().getOutput().getDataType() != DataType.MATRIX)) {
		// remove exception for now
		//		throw new LanguageException(
		//				"Matrix multiplication not supported for scalars",
		//				LanguageException.LanguageErrorCodes.INVALID_PARAMETERS);
			}
			if (this.getLeft().getOutput().getDim2() != -1
					&& this.getRight().getOutput().getDim1() != -1
					&& this.getLeft().getOutput().getDim2() != this.getRight()
							.getOutput().getDim1()) 
			{
				raiseValidateError("invalid dimensions for matrix multiplication (k1="+this.getLeft().getOutput().getDim2()+", k2="+this.getRight().getOutput().getDim1()+")", 
						            conditional, LanguageException.LanguageErrorCodes.INVALID_PARAMETERS);
			}
			output.setDimensions(this.getLeft().getOutput().getDim1(), this
					.getRight().getOutput().getDim2());
		}

		this.setOutput(output);
	}

	private void checkAndSetDimensions(DataIdentifier output, boolean conditional)
			throws LanguageException {
		Identifier left = this.getLeft().getOutput();
		Identifier right = this.getRight().getOutput();
		Identifier pivot = null;
		Identifier aux = null;

		if (left.getDataType() == DataType.MATRIX) {
			pivot = left;
			if (right.getDataType() == DataType.MATRIX) {
				aux = right;
			}
		} else if (right.getDataType() == DataType.MATRIX) {
			pivot = right;
		}

		if ((pivot != null) && (aux != null)) 
		{
			//check dimensions binary operations (if dims known)
			if (isSameDimensionBinaryOp(this.getOpCode()) && pivot.dimsKnown() && aux.dimsKnown() )
			{
				if(   (pivot.getDim1() != aux.getDim1() && aux.getDim1()>1)  //number of rows must always be equivalent if not row vector
				   || (pivot.getDim2() != aux.getDim2() && aux.getDim2()>1)) //number of cols must be equivalent if not col vector
				{
					raiseValidateError("Mismatch in dimensions for operation "+ this.toString(), conditional, LanguageException.LanguageErrorCodes.INVALID_PARAMETERS);
				} 
			}
		}

		//set dimension information
		if (pivot != null) {
			output.setDimensions(pivot.getDim1(), pivot.getDim2());
		}
	}
	
	public String toString() {

		return "(" + _left.toString() + " " + _opcode.toString() + " "
				+ _right.toString() + ")";

	}

	@Override
	public VariableSet variablesRead() {
		VariableSet result = new VariableSet();
		result.addVariables(_left.variablesRead());
		result.addVariables(_right.variablesRead());
		return result;
	}

	@Override
	public VariableSet variablesUpdated() {
		VariableSet result = new VariableSet();
		result.addVariables(_left.variablesUpdated());
		result.addVariables(_right.variablesUpdated());
		return result;
	}

	public static boolean isSameDimensionBinaryOp(BinaryOp op) {
		return (op == BinaryOp.PLUS) || (op == BinaryOp.MINUS)
				|| (op == BinaryOp.MULT) || (op == BinaryOp.DIV)
				|| (op == BinaryOp.MODULUS) || (op == BinaryOp.INTDIV)
				|| (op == BinaryOp.POW);
		
	}
}
