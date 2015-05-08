/******************************************************************************
 * Copyright (c) 2015 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *****************************************************************************/
 package com.ibm.research.rdf.store.sparql11.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibm.research.rdf.store.runtime.service.types.TypeMap;
import com.ibm.research.rdf.store.sparql11.XTree;

public class BindFunctionCall {

	protected List<Variable> vars;
	protected String name;
	
	
	public BindFunctionCall() {
		this.vars = new ArrayList<Variable>();
		this.name = "";
	}
	

	public List<Variable> getVars() {
		return vars;
	}
	
	public void addVar(Variable v) {
		vars.add(v);
	}
	
	public void addVar(String v) {
		vars.add(new Variable(v));
	}
	
	public String getName() {
		return name;
	}

	public void setName(String s) {
		name = s;
	}
	
	public void setName(XTree s) {
		name = s.getText();
	}
	
	public Set<Variable> getVariables() {
		Set<Variable> ret = new HashSet<Variable>();
		ret.addAll(vars);
		return ret;
	}
	
	public Set<Variable> gatherVariables() {
		Set<Variable> ret = new HashSet<Variable>();
		ret.addAll(vars);
		return ret;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(name)
		  	.append("(");
		for(Variable v: vars)
		{
			sb.append(v+" ");
		}
		sb.append(")");
		return sb.toString();
	}
	
}