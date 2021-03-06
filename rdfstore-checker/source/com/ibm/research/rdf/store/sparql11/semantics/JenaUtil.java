package com.ibm.research.rdf.store.sparql11.semantics;

import static com.ibm.research.rdf.store.sparql11.semantics.ExpressionUtil.bitWidth;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.NoSuchElementException;
import java.util.Set;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.graph.impl.LiteralLabel;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.rdf.model.AnonId;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.sparql.algebra.Algebra;
import com.hp.hpl.jena.sparql.algebra.Op;
import com.hp.hpl.jena.sparql.algebra.OpVisitorBase;
import com.hp.hpl.jena.sparql.algebra.op.OpBGP;
import com.hp.hpl.jena.sparql.algebra.op.OpDistinct;
import com.hp.hpl.jena.sparql.algebra.op.OpExtend;
import com.hp.hpl.jena.sparql.algebra.op.OpFilter;
import com.hp.hpl.jena.sparql.algebra.op.OpGraph;
import com.hp.hpl.jena.sparql.algebra.op.OpGroup;
import com.hp.hpl.jena.sparql.algebra.op.OpJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpLeftJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpMinus;
import com.hp.hpl.jena.sparql.algebra.op.OpPath;
import com.hp.hpl.jena.sparql.algebra.op.OpProject;
import com.hp.hpl.jena.sparql.algebra.op.OpService;
import com.hp.hpl.jena.sparql.algebra.op.OpTable;
import com.hp.hpl.jena.sparql.algebra.op.OpUnion;
import com.hp.hpl.jena.sparql.core.TriplePath;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.expr.ExprAggregator;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

import kodkod.ast.Relation;
import kodkod.engine.Evaluator;
import kodkod.engine.config.Options;
import kodkod.engine.config.Options.IntEncoding;
import kodkod.engine.fol2sat.UnboundLeafException;
import kodkod.instance.Instance;
import kodkod.instance.Tuple;
import kodkod.instance.TupleSet;

public class JenaUtil {

	static URI toURI(Node n) throws URISyntaxException {
		return new URI(n.getURI());
	}

	static Node fromURI(String uri) {
		return NodeFactory.createURI(uri);
	}
	
	static String toBlank(Node n) {
		return n.getBlankNodeLabel();
	}

	static Node fromBlank(String s) {
		return NodeFactory.createAnon(new AnonId(s));
	}
		
	public static Object toAtom(Node n) throws URISyntaxException {
		if (n.isURI()) {
			return toURI(n);
		} else if (n.isBlank()) {
			return toBlank(n);
		} else {
			assert n.isLiteral();
			return toAtom(n.getLiteral());
		}
	}

	public static Query parse(String queryString) throws MalformedURLException, IOException {
		if (new URL(queryString).openConnection().getInputStream() != null) {
			return QueryFactory.read(queryString);
		} else {
			return QueryFactory.create(queryString);
		}
	}

	public static Op compile(String queryString) throws MalformedURLException, IOException {
		return Algebra.compile(parse(queryString));
	}

	public static Op compile(Query query) throws MalformedURLException, IOException {
		return Algebra.compile(query);
	}
	
	static RDFNode fromLiteral(Model m, Pair<String,Object> o, BasicUniverse u, Instance t2) {
		try {
			Relation me = Relation.unary("me");
			t2.add(me, t2.universe().factory().setOf(t2.universe().factory().tuple(o)));
			Options opt = new Options();
			opt.setIntEncoding(IntEncoding.TWOSCOMPLEMENT);
			opt.setBitwidth(bitWidth);
			Evaluator e = new Evaluator(t2, opt);
			if (o.snd instanceof String) {
				TupleSet langs = e.evaluate(me.join(QuadTableRelations.literalLanguages));
				if (! langs.isEmpty()) {
					return m.createLiteral(o.fst, langs.iterator().next().atom(0).toString());
				} else {
					return m.createLiteral(o.fst, (String)o.snd);				
				}
			} else if (o.snd == null) {
				return m.createLiteral(o.fst);			
			} else {
				TupleSet tt = e.evaluate(me.join(QuadTableRelations.literalDatatypes));
				String type = String.valueOf(tt.iterator().next().atom(0));
				if (ExpressionUtil.numericTypeNames.contains(type)) {
					int v = e.evaluate(me.join(QuadTableRelations.literalValues).sum());
					return m.createTypedLiteral(v, type);
				} else {
					Object v = e.evaluate(me.join(QuadTableRelations.literalValues)).iterator().next().atom(0);
					return m.createTypedLiteral(v, type);			
				}
			}
		} catch (UnboundLeafException | NoSuchElementException ee) {
			return m.createTypedLiteral(o.fst, String.valueOf(o.snd));
		}
	}

	@SuppressWarnings("unchecked")
	public static RDFNode fromAtom(Model m, Object o, BasicUniverse u, Instance t2) {
		if (o instanceof Pair<?,?>) {
			return fromLiteral(m, (Pair<String,Object>) o, u, t2);
		} else {
			return m.createResource(o.toString());
		}
	}
	
	public static Statement fromTuple(Model m, Tuple t, BasicUniverse u, Instance t2) {
		Resource s = m.createResource(String.valueOf(t.atom(1)));
		Property p = m.createProperty(String.valueOf(t.atom(2)));
		RDFNode o = fromAtom(m, t.atom(3), u, t2);
		return m.createStatement(s, p, o);
	}
	
	public static void addTupleSet(Dataset dataset, TupleSet tt, BasicUniverse u, Instance t2) {
		for(Tuple t : tt) {
			Object graph = t.atom(0);
			Model m = QuadTableRelations.defaultGraph.equals(graph)? dataset.getDefaultModel(): dataset.getNamedModel(graph.toString());
			m.add(fromTuple(m, t, u, t2));
		}
	}
	
	public static Pair<String, Object> toAtom(LiteralLabel l) {
		Object snd = null;
		if (l.getDatatypeURI() != null) {
			try {
				snd = new URI(l.getDatatypeURI());
			} catch (URISyntaxException e) {
				assert false : l.getDatatypeURI() + " is not a datatype";
			}
		}
		if (l.language() != null && !"".equals(l.language())) {
			snd = l.language().toLowerCase();
		}
		return Pair.make(l.getLexicalForm(), snd);
	}

	public static Set<String> scope(Op top) {
		final Set<String> result = HashSetFactory.make();
		top.visit(new OpVisitorBase() {

			@Override
			public void visit(OpMinus opMinus) {
				opMinus.getLeft().visit(this);
			}

			@Override
			public void visit(OpFilter opFilter) {
				opFilter.getSubOp().visit(this);
			}

			@Override
			public void visit(OpBGP opBGP) {
				for(Triple t : opBGP.getPattern().getList()) {
					if (t.getSubject().isVariable()) {
						result.add(t.getSubject().getName());
					}
					if (t.getPredicate().isVariable()) {
						result.add(t.getPredicate().getName());
					}
					if (t.getObject().isVariable()) {
						result.add(t.getObject().getName());
					}
				}
			}

			@Override
			public void visit(OpPath opPath) {
				TriplePath t = opPath.getTriplePath();
				if (t.getSubject().isVariable()) {
					result.add(t.getSubject().getName());
				}
				if (t.getObject().isVariable()) {
					result.add(t.getObject().getName());
				}
			}

			@Override
			public void visit(OpJoin opJoin) {
				opJoin.getLeft().visit(this);
				opJoin.getRight().visit(this);
			}

			@Override
			public void visit(OpLeftJoin opLeftJoin) {
				opLeftJoin.getLeft().visit(this);
				opLeftJoin.getRight().visit(this);
			}

			@Override
			public void visit(OpUnion opUnion) {
				opUnion.getLeft().visit(this);
				opUnion.getRight().visit(this);
			}

			@Override
			public void visit(OpGraph opGraph) {
				opGraph.getSubOp().visit(this);
				if (opGraph.getNode().isVariable()) {
					result.add(opGraph.getNode().getName());
				}
			}

			@Override
			public void visit(OpService opService) {
				opService.getSubOp().visit(this);
				if (opService.getService().isVariable()) {
					result.add(opService.getService().getName());
				}
			}

			@Override
			public void visit(OpTable opTable) {
				result.addAll(opTable.getTable().getVarNames());
			}

			@Override
			public void visit(OpExtend opExtend) {
				opExtend.getSubOp().visit(this);
				for(Var v : opExtend.getVarExprList().getVars()) {
					result.add(v.getName());
				}
			}

			@Override
			public void visit(OpProject opProject) {
				opProject.getSubOp().visit(this);
				for (Var v : opProject.getVars()) {
					result.add(v.getName());
				}
			}

			@Override
			public void visit(OpGroup opGroup) {
				opGroup.getSubOp().visit(this);
				for (Var v : opGroup.getGroupVars().getVars()) {
					result.add(v.getName());
				}
				for(ExprAggregator agg : opGroup.getAggregators()) {
					result.add(agg.getAggVar().getVarName());
				}
			}

			@Override
			public void visit(OpDistinct opDistinct) {
				opDistinct.getSubOp().visit(this);
			}				
		});
		return result;
	}
}
