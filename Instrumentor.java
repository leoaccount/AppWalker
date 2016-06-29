/*
  Copyright (c) 2011,2012,2014
   Saswat Anand (saswat@gatech.edu)
   Mayur Naik  (naik@cc.gatech.edu)
   Julian Schuette (julian.schuette@aisec.fraunhofer.de)
   Leo (leohoop@foxmail.com)
   
  All rights reserved.
  
  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are met: 
  
  1. Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer. 
  2. Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution. 
  
  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  
  The views and conclusions contained in the software and documentation are those
  of the authors and should not be interpreted as representing official policies, 
  either expressed or implied, of the FreeBSD Project.
*/

package acteve.instrumentor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import acteve.symbolic.string.SymbolicString;

import soot.ArrayType;
import soot.Body;
import soot.Immediate;
import soot.IntType;
import soot.Local;
import soot.PatchingChain;
import soot.PrimType;
import soot.RefLikeType;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.AbstractStmtSwitch;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.BinopExpr;
import soot.jimple.CastExpr;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.ConditionExpr;
import soot.jimple.Constant;
import soot.jimple.FieldRef;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InstanceOfExpr;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.LengthExpr;
import soot.jimple.LookupSwitchStmt;
import soot.jimple.MonitorStmt;
import soot.jimple.NegExpr;
import soot.jimple.NewArrayExpr;
import soot.jimple.NewExpr;
import soot.jimple.NewMultiArrayExpr;
import soot.jimple.NullConstant;
import soot.jimple.ReturnStmt;
import soot.jimple.StaticFieldRef;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.jimple.TableSwitchStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JVirtualInvokeExpr;
import soot.tagkit.BytecodeOffsetTag;
import soot.tagkit.LineNumberTag;
import soot.tagkit.SourceFileTag;
import soot.tagkit.SourceLineNumberTag;
import soot.tagkit.SourceLnPosTag;
import soot.tagkit.StringTag;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.SimpleLocalDefs;
import soot.util.Chain;

public class Instrumentor extends AbstractStmtSwitch {
	public static Logger log = LoggerFactory.getLogger(Instrumentor.class);
	private final RWKind rwKind;
	private final String outDir;
	private final String sdkDir;
    private final MethodSubsigNumberer methSubsigNumberer;
    private final MethodSigNumberer methSigNumberer;
    private final FieldSigNumberer fieldSigNumberer;
	private final Filter fieldsWhitelist;
	private final Filter fieldsBlacklist;
	@SuppressWarnings("unused")
	private final Filter methodsWhitelist;
	private final boolean instrAllFields;
	private final Map<SootField, SootField> fieldsMap;
	private final Map<SootField, SootField> idFieldsMap;
    private final List<String> condIdStrList;
	// map from a original local to its corresponding shadow local
	private final Map<Local, Local> localsMap;
	private SootMethod currentMethod;
	private int sigIdOfCurrentMethod;	
	private static HashSet<String> TARGET_METHODS = new HashSet<String>();
	
	//#### heap 
	//#### classes who declares access$
	//private List<SootClass> declaringclasses = new ArrayList<SootClass>();
		
	static {
		// Target definitions
		//#### 2016.06.20 target at sinks
		//segmented data-flow analysis will consider intent methods
		//TODO: replace the hard coded flowdroid dir
		if (Main.SEGMENTED) {
			 try{
					File srcsnkFile=new File("/home/julian/workspace/didfail/soot-infoflow-android/SourcesAndSinks.txt");
					BufferedReader br = new BufferedReader(new FileReader(srcsnkFile));
					String line = "";
					while ((line = br.readLine())!=null) {
						if (line.contains(" -> _SINK_") && !line.startsWith("%"))
							TARGET_METHODS.add(line.substring(0, line.length()-10));
						else
							continue;
					}
					br.close();
				}catch(Exception e){
					throw new Error(e);
				}
		} else {
			TARGET_METHODS.addAll( Arrays.asList(new String[] {
	//				"<java.lang.Class: T newInstance()>",
	//				 "<java.lang.Class: newInstance()>",
	//				 "<java.lang.Object: newInstance()>",
	//				 "<java.lang.Class: T newInstance(java.lang.Object...)>",
	//				 "<java.lang.Class: newInstance(java.lang.Object...)>",
	//				 "<java.lang.Class: java.lang.reflect.Constructor<T> getConstructor(java.lang.Class<?>...)>",
	//				 "<java.lang.Class: java.lang.reflect.Constructor getConstructor(java.lang.Class...)>",
	//				 "<java.lang.Class: java.lang.reflect.Constructor<?>[] getConstructors()>",
	//				 "<java.lang.Class: java.lang.reflect.Constructor[] getConstructors()>",
	//				 "<java.lang.Class: java.lang.reflect.Constructor<?>[] getDeclaredConstructors()>",
	//				 "<java.lang.Class: java.lang.reflect.Constructor[] getDeclaredConstructors()>",
	//				 "<java.lang.Class: java.lang.Class<T> forName(java.lang.String)>",
	//				 "<java.lang.Class: java.lang.Class forName(java.lang.String)>",
	//
	//				 "<java.lang.ClassLoader: java.lang.Class<T> loadClass(java.lang.String)>",
	//				 "<java.lang.ClassLoader: java.lang.Class loadClass(java.lang.String)>",
	//				 "<java.lang.ClassLoader: java.lang.Class<T> loadClass(java.lang.String,boolean)>",
	//				 "<java.lang.ClassLoader: java.lang.Class loadClass(java.lang.String,boolean)>",
	//				 "<java.lang.ClassLoader: void <init>()>",
	//				 "<java.lang.ClassLoader: void <init>(java.lang.ClassLoader)>",
	//				 "<java.lang.ClassLoader: java.lang.ClassLoader getSystemClassLoader()>",
	//
	//				 "<java.net.URLClassLoader: void <init>(java.net.URL[])>",
	//				 "<java.net.URLClassLoader: void <init>(java.net.URL[],java.lang.ClassLoader)>",
	//				 "<java.net.URLClassLoader: void <init>(java.net.URL[],java.lang.ClassLoader,java.net.URLStreamHandlerFactory)>",
	//
	//				 "<java.security.SecureClassLoader: void <init>()>",
	//				 "<java.security.SecureClassLoader: void <init>(java.lang.ClassLoader)>",
					 
					
					"<dalvik.system.BaseDexClassLoader: void <init>(Java.lang.String,java.io.File,java.lang.String,java.lang.ClassLoader)>",
	
					"<dalvik.system.DexClassLoader: void <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.ClassLoader)>",
	
					"<dalvik.system.PathClassLoader: void <init>(Java.lang.String,java.lang.ClassLoader)>",
					 "<dalvik.system.PathClassLoader: void <init>(Java.lang.String,Java.lang.String,java.lang.ClassLoader)>"
					}));
		}
	};

	private boolean doRW() {
		return doRW(null);
	}

	private boolean doRW(SootField fld) {
		if (rwKind == RWKind.NONE)
			return false;
		if (sdkDir != null) {
			//instrumenting app
			return true;
		}
		if (fld == null) {
			//ignore array elems read/write in sdk code
			return false; 
		}
		if(instrAllFields) {
			return fld.getDeclaringClass().getName().startsWith("android.");
		}
		
		String fldSig = fld.getSignature();
		if(fieldsWhitelist == null || fieldsWhitelist.matches(fldSig)) {
			//#### whitelist: empty or contains the field both ok
			//#### blacklist: empty or not_contains the field both ok
			return fieldsBlacklist == null ? true : !fieldsBlacklist.matches(fldSig);
		} 
		return false;
	}

	// sdkDir == null iff we are instrumenting framework
	public Instrumentor(RWKind _rwKind, 
						String _outDir, 
						String _sdkDir, 
						String _fldsWLFile, 
						String _fldsBLFile, 
						String _methsWLFile,
						boolean _instrAllFields) {
		assert (_outDir != null);
		rwKind = _rwKind;
		outDir = _outDir;
		sdkDir = _sdkDir;
 		fieldsMap = new HashMap<SootField, SootField>();
		idFieldsMap = (rwKind == RWKind.ID_FIELD_WRITE) ? new HashMap<SootField, SootField>() : null;
 		condIdStrList = new ArrayList<String>();
		localsMap = new HashMap<Local, Local>();
		//writeMap = new HashMap<Integer, Set<Integer>>();
		methSubsigNumberer = new MethodSubsigNumberer();
		methSigNumberer = new MethodSigNumberer();
		fieldSigNumberer = new FieldSigNumberer();
		
		fieldsWhitelist = _fldsWLFile != null ? new Filter(_fldsWLFile) : null;
		fieldsBlacklist = _fldsBLFile != null ? new Filter(_fldsBLFile) : null;
		methodsWhitelist = _methsWLFile != null ? new Filter(_methsWLFile) : null;
		instrAllFields = _instrAllFields;
	}

	public void instrument(Set<SootMethod> methods) {
		for (SootMethod klass : methods) {
			klass.getDeclaringClass().setApplicationClass();//#### set method's DeclaringClass as app class (not lib class)
			
			//#### heap
			//#### determine classes who declares access$ 
			// instrument access$i method, it's easy to find the declaring class
			/*if(klass.getName().contains("access$"))
				declaringclasses.add(klass.getDeclaringClass());*/
			
			addSymbolicFields(klass.getDeclaringClass());//#### add sym fields in DeclaringClass
		}

		loadFiles();

		for (SootMethod m : methods) {
			if (!m.isConcrete())
				continue;

			//For Debugging, attach the subsig number to each method.
			int subSig = methSubsigNumberer.getOrMakeId(m);
			m.addTag(new StringTag(String.valueOf(subSig)));
			
			
			if (ModelMethodsHandler.modelExistsFor(m)) {
				// do not instrument method if a model for it exists
				log.debug("skipping instrumentation of " + m + " (model exists)");
				continue;
			}
			instrument(m);
		}		

		saveFiles();//#### save debug/config/temp logs
	}

	/**
	 * Add symbolic counterparts to fields in a class.
	 * 
	 * @param c
	 */
	private void addSymbolicFields(SootClass c) {
		for (Iterator<SootField> it = c.getFields().snapshotIterator(); it.hasNext();) {
			SootField origField = (SootField) it.next();
			if(addSymLocationFor(origField.getType())) {//#### check if the field can be traced as symbol
				SootField symField = new SootField(origField.getName()+"$sym",
												   G.EXPRESSION_TYPE, origField.getModifiers());
				if (!c.declaresFieldByName(symField.getName()))
					c.addField(symField);//#### symField.declaringClass = c(MainAct)
				fieldsMap.put(origField, symField);
			}

			//#### each heap should have id
            if(rwKind == RWKind.ID_FIELD_WRITE && doRW(origField)){
				SootField idField = new SootField(origField.getName()+"$a3tid", IntType.v(), origField.getModifiers());
				log.debug("Adding field " + idField.getName() + " for " + origField.getName() + " in " + c.getName());
				if (!c.declaresFieldByName(idField.getName()))
					//#### heap
					//#### it is best to just add symField and idFiled to the owner class of the field (or access$ method)
					//#### but odd conflict occurs, so we didn't added any idField in addSymbolicFields()
					;//c.addField(idField);//#### idField.declaringClass = c
				idFieldsMap.put(origField, idField);
            }
		}
	}

	private void instrument(SootMethod method) {
		//#### method = MainActivity$1: void onClick(android.view.View)>
		
		//#### ?? For model class: add symbolic field (class might be created dynamically)
		log.info("Instrumenting " + method.getSignature());
		SwitchTransformer.transform(method);//#### transform switch to if-else?
 		localsMap.clear();

		Body body = method.retrieveActiveBody();		
		G.editor.newBody(body, method);
		addSymLocals(body);//#### 1. add symbolic localVars for each (asm) registers inside method's body (if type supported)
		List<Local> params = new ArrayList<Local>();
	
		
		currentMethod = method;
		sigIdOfCurrentMethod = methSigNumberer.getOrMakeId(method);

		//#### 2. symbolic instrumentation for each stmts
		while (G.editor.hasNext()) {
			Stmt s = G.editor.next();
			if (paramOrThisIdentityStmt(s)) {//#### param/this
				params.add((Local) ((IdentityStmt) s).getLeftOp());
			} else if (s.containsInvokeExpr()) {//#### xxx.f(), e.g., String.contains("unknown")
				if (s instanceof AssignStmt && isSupportedByModel(s.getInvokeExpr())) {//#### z3t-str only support contains()
					//Treat like binop stmt
					handleModelledInvokeExpr((AssignStmt) s);
				} else {
					handleInvokeExpr(s);
				}
			} else if (!s.branches())//#### other non-branch stmts, e.g., $r3 = BOARD
				s.apply(this);//#### caseAssignStmt (branch to)-> handleloadstmt
		}

		//#### 3. Prologue: a3targs$symargs = argpop(...)
		//it is done at the end for a good reason
		insertPrologue(body, params);//#### only params appeared in paramStmt are considered
		
		//#### 4. Instrument conditions (if branches); note we didn't instrument branchStmt yet
		instrumentConds(body);
		
		G.debug(method, Main.DEBUG);
	}
	
	/**
	 * Handles invokes supported by the model, e.g. String.contains.
	 * s is expected to be an assignStmt with an InvokeExpr.
	 */
	private void handleModelledInvokeExpr(AssignStmt s) {
		if (!s.containsInvokeExpr()) {
			System.err.println("Unexpected: handleModelledInvokeExpr with non-invoke stmt: " + s.toString());
			return;
		}
		
        if (!(s.getLeftOp() instanceof Immediate)) {
			System.err.println("Unexpected: handleModelledInvokeExpr with non-immediate local: " + s.toString());
        	return;
        }
        
		//TODO Consider negate
        JVirtualInvokeExpr rightOp = (JVirtualInvokeExpr) s.getRightOp();//#### virtualinvoke $r3.<java.lang.String: boolean contains(java.lang.CharSequence)>("unknown")
        Immediate op1 = (Immediate) rightOp.getBase();//#### $r3
        Immediate op2 = (Immediate) s.getInvokeExpr().getArg(0);//#### "unknown"
        
		Immediate symOp1 = op1 instanceof Constant ? NullConstant.v() : localsMap.get((Local) op1);//#### $r3$sym
		Immediate symOp2 = op2 instanceof Constant ? NullConstant.v() : localsMap.get((Local) op2);//#### null
		
		String methodName = G.binopSymbolToMethodName.get(s.getInvokeExpr().getMethod().getSignature()); //#### _contains //TODO add java.lang.string.contains to binopsymboltomethodname
		//#### 2016.06.20
		if (methodName.equals("_contains")) {
			String methodSig = G.EXPRESSION_CLASS_NAME + " " + methodName + "(" + G.EXPRESSION_CLASS_NAME + "," + 
					G.EXPRESSION_CLASS_NAME + "," + RefType.v("java.lang.String") + "," + RefType.v("java.lang.String") + ")";//#### acteve.symbolic.integer.Expression _contains(acteve.symbolic.integer.Expression,acteve.symbolic.integer.Expression,java.lang.String,java.lang.String)
			SootMethodRef ref = G.symOpsClass.getMethod(methodSig).makeRef();//#### <acteve.symbolic.SymbolicOperations: acteve.symbolic.integer.Expression _contains(acteve.symbolic.integer.Expression,acteve.symbolic.integer.Expression,java.lang.String,java.lang.String)>
			StaticInvokeExpr rhs = G.staticInvokeExpr(ref, Arrays.asList(new Immediate[]{symOp1, symOp2, op1, op2}));//#### SymbolicOperations._contains($r3$sym, null, $r3, "unknown")
	
			G.assign(symLocalfor((Immediate) s.getLeftOp()), rhs);
			
		} else if (methodName.equals("_eq")) {
			String methodSig = G.EXPRESSION_CLASS_NAME + " " + methodName + "(" + G.EXPRESSION_CLASS_NAME + "," + 
					G.EXPRESSION_CLASS_NAME + "," + RefType.v("java.lang.Object") + "," + RefType.v("java.lang.Object") + ")";//#### acteve.symbolic.integer.Expression _contains(acteve.symbolic.integer.Expression,acteve.symbolic.integer.Expression,java.lang.String,java.lang.String)
			SootMethodRef ref = G.symOpsClass.getMethod(methodSig).makeRef();//#### <acteve.symbolic.SymbolicOperations: acteve.symbolic.integer.Expression _contains(acteve.symbolic.integer.Expression,acteve.symbolic.integer.Expression,java.lang.String,java.lang.String)>
			StaticInvokeExpr rhs = G.staticInvokeExpr(ref, Arrays.asList(new Immediate[]{symOp1, symOp2, op1, op2}));//#### SymbolicOperations._contains($r3$sym, null, $r3, "unknown")

			G.assign(symLocalfor((Immediate) s.getLeftOp()), rhs);
		}
	}
	
	/**
	 * Returns true if invocation should be reflected in Z3 model.
	 * 
	 */
	//// Z3-str only support: contains
	private boolean isSupportedByModel(InvokeExpr expr) {
		String m = expr.getMethod().getSignature();
		if (m.equals("<java.lang.String: boolean contains(java.lang.CharSequence)>") 
			|| m.equals("<java.lang.String: java.lang.String concat(java.lang.String)>")
			//#### 2016.06.20
			//boolean java.lang.String.contains(CharSequence cs)
			//String java.lang.String.concat(String string)
			//boolean java.lang.String.equals(Object object)
			|| m.equals("<java.lang.String: boolean equals(java.lang.Object)>")
			) {
			return true;
		}
		return false;
	}
	


    private static String getStr(Unit h, String methodSigAndFileStr) {
        int bci;
        if (h.hasTag("BytecodeOffsetTag"))
            bci = ((BytecodeOffsetTag) h.getTag("BytecodeOffsetTag")).getBytecodeOffset();
        else
            bci = -1;
        int lineNum;
        if (h.hasTag("LineNumberTag"))
            lineNum = ((LineNumberTag) h.getTag("LineNumberTag")).getLineNumber();
        else if (h.hasTag("SourceLineNumberTag"))
            lineNum = ((SourceLineNumberTag) h.getTag("SourceLineNumberTag")).getLineNumber();
        else if (h.hasTag("SourceLnPosTag"))
            lineNum = ((SourceLnPosTag) h.getTag("SourceLnPosTag")).startLn();
        else
            lineNum = 0;
        return bci + "!" + methodSigAndFileStr + lineNum + ")";
    }

    private static String getMethodSigAndFileStr(Body body) {
        SootMethod m = body.getMethod();
        SootClass c = m.getDeclaringClass();
        String fileName;
        if (c.hasTag("SourceFileTag"))
            fileName = ((SourceFileTag) c.getTag("SourceFileTag")).getSourceFile();
        else
            fileName = "unknown_file";
        return m.getSignature() + " (" + fileName + ":";
    }

	/**
	 * Instrument conditions (if branches).
	 * 
	 * @param body
	 */
    private void instrumentConds(Body body) {
		String methodSigAndFileStr = getMethodSigAndFileStr(body);
        int entryCondId = condIdStrList.size();
        
        
        
        // collect all conditional branches in this method
        List<IfStmt> conds = new ArrayList<IfStmt>();
		Chain<Unit> units = body.getUnits();
		Unit lastParamStmt = units.getFirst();
		IdentityStmt thisstmt = InstrumentationHelper.getThisStmt(body);
		Value thisLocal = thisstmt!=null?thisstmt.getLeftOp():null;
		
		HashSet<Value> toInitialize = new HashSet<Value>(body.getLocalCount());
        for (Unit u : units) {
        	//Collect all register used in monitor stmts to initialize them to avoid possible VRFY errors
        	if (u instanceof MonitorStmt) {        		
        		Value s = ((MonitorStmt) u).getOp();
        		if (s instanceof Local && s!=thisLocal)
        			toInitialize.add(s);
        	}
        	if (u instanceof IfStmt) {
                conds.add((IfStmt) u);
                String str = getStr(u, methodSigAndFileStr);
                condIdStrList.add(str);
            } else if (u instanceof LookupSwitchStmt || u instanceof TableSwitchStmt) {
                throw new RuntimeException("Unexpected branch stmt kind: " + u);
            }
        }

        if (conds.size() <= 0) {
            //no branches in method -> done
            return;
        }

        Local symVar = G.newLocal(G.EXPRESSION_TYPE);//#### _sym_tmp_4
        for (int i = 0; i < conds.size(); i++) {//#### for each branch condition
			IfStmt ifStmt = conds.get(i);//#### if $z0 != 0 goto return
			int absCondId = entryCondId + i;
			ConditionExpr condExp = (ConditionExpr) ifStmt.getCondition();//#### get branch condition, i.e.,$z0 != 0
			if (condExp.getOp1() instanceof Constant && condExp.getOp2() instanceof Constant) {
				// Only constants are compared. No need for symbolic tracing
				//#### both ops are constant, do not backtrack
				continue;
			}
			//Check UD chain for references to String methods TODO UD chain is only intraprocedural. Extend to interproc. backwards propagation
			Value realV = null;
			if (condExp.getOp1() instanceof Local) {
				List<Unit> defs = generateUseDefChain(body, ifStmt, (Local) condExp.getOp1());//#### single-elem UseDefChain: $z0 = String.contains("unknown")
				ListIterator<Unit> it = defs.listIterator(defs.size());
				while (it.hasPrevious()) {//#### backtracing conditional val's definition stmt, i.e., $z0 = xxx.contains(xxx)
					Unit def = it.previous();
					System.out.println(def instanceof AssignStmt);//#### chainElem is assignStmt
					
					//#### match the pattern (AssignStmt & containsInvokeExpr): $z0 != 0 --> $z0 := $x.Contains($y)
					if (def instanceof AssignStmt && ((AssignStmt) def).containsInvokeExpr()) {
					Unit symDef = units.getPredOf(def);//#### get the stmt defining sym_tmp var in previous of def
					//#### check the stmt right before defStmt of $z0
					//#### if have been instrmted as symops (i.e., _contains), 
					//#### we use _sym_tmp_5 to keep track of expr (and then assume(_sym_tmp_5)), rather than z0.
					//#### this is more optimized
						if (symDef instanceof AssignStmt && ((AssignStmt) symDef).containsInvokeExpr() && ((AssignStmt) symDef).getInvokeExpr().getMethod().getDeclaringClass().getName().equals(G.SYMOPS_CLASS_NAME)) {
							SootMethod m = ((AssignStmt) symDef).getInvokeExpr().getMethod();//#### SymbolicOperations._contains()
							
							//#### 2016.06.20 add _eq
							if (m.getName().equals("_contains")
									|| m.getName().equals("_eq")) { //TODO Handle more operations supported by Z3 here
								realV = ((AssignStmt) symDef).getLeftOp();//#### $z0$sym
								
								//#### 2016.06.21 backtrack defining (modifying) event handler (i.e., onActReslt()), even if inter-procedure
								//#### and, instrument and force execution of the backtracked trace
								//#### TODO: add more support except contains and equals, even those which cannot be optimized by conditional-op-UD-chain-backtracking
								//#### TODO: now only track left String
								//#### TODO: now only track immediate outer class
								Local op2 = (Local) ((soot.jimple.VirtualInvokeExpr)((AssignStmt) def).getInvokeExpr()).getBase();
								List<Unit> defs2 = generateUseDefChain(body, def, op2);// what's inside???
								ListIterator<Unit> it2 = defs2.listIterator(defs2.size());
								while (it2.hasPrevious()) {
									Unit def2 = it2.previous();
									//#### match the pattern
									
									if (def2 instanceof AssignStmt && ((AssignStmt) def2).containsInvokeExpr()) {
										/* TODO
										Unit symDef = units.getPredOf(def);
										if (symDef instanceof AssignStmt && ((AssignStmt) symDef).containsInvokeExpr() && ((AssignStmt) symDef).getInvokeExpr().getMethod().getDeclaringClass().getName().equals(G.SYMOPS_CLASS_NAME)) {
											SootMethod m = ((AssignStmt) symDef).getInvokeExpr().getMethod();//#### SymbolicOperations._contains()
											if (m.getName().equals("_contains")
													|| m.getName().equals("_eq")) { 
												realV = ((AssignStmt) symDef).getLeftOp();//#### $z0$sym
												break;
											}
										*/
										} else {
											//log.warn("No symbolic counterpart found for " + def);
										}
									}
								//#### 2016.06.21 END
								
								break;
							}
						} else {
							log.warn("No symbolic counterpart found for " + def);
						}
					}
				}
			}
			IntConstant condId = IntConstant.v(absCondId);

			// Assign symbolic value of concrete expr 'condExp' to local var 'symVar'.
			Value v = handleBinopExpr(condExp, false, localsMap);//#### SymbolicOperations._ne($z0$sym, null, $z0, 0)

			if(v == null)
				v = NullConstant.v();
			if (realV !=null) { //TODO fixme! Assign leftOp of stmt before "stuff", if stmt is staticinvoke symbolicoperations._contains
				v = realV;//#### overwrite v as $z0$sym
			}
			Stmt symAsgnStmt = G.jimple.newAssignStmt(symVar, v);//#### _sym_tmp_4 = $z0$sym

			Stmt assumeFlsStmt, assumeTruStmt;
			assumeFlsStmt = G.jimple.newInvokeStmt(G.staticInvokeExpr(G.assume,
				Arrays.asList(new Immediate[]{symVar, condId, IntConstant.v(0)})));//#### Util.assume(_sym_tmp_4, 9, 0)
			assumeTruStmt = G.jimple.newInvokeStmt(G.staticInvokeExpr(G.assume,
				Arrays.asList(new Immediate[]{symVar, condId, IntConstant.v(1)})));//#### Util.assume(_sym_tmp_4, 9, 1)

			Stmt oldTarget = ifStmt.getTarget();//#### return
			
			//Insert symbolic condition before concrete if-statement
			units.insertBefore(symAsgnStmt, ifStmt);//#### insert _sym_tmp_4 = $z0$sym before concrete if-statement
						
			//Insert symbolic "false" assumption immediately after concrete if-statement
			units.insertAfter(assumeFlsStmt, ifStmt);
			
			/* 
			 * The layout of statements will be reordered like this:
			 * 
			 * 			symAsgnStmt
			 *			if <condition> goto assumeTrue
			 *			assume false
			 *			goto oldTarget			  (gotoOldTargetStmt1)
			 * assumeTrue:
			 *			assume true				  (assumeTruStmt)
			 * 			goto oldTarget            (gotoOldTargetStmt2)
			 * oldTarget:
			 *          <original true branch>
			 *///#### concolicexample does not have complex true branch
			Stmt gotoOldTargetStmt1 = G.jimple.newGotoStmt(oldTarget);
			Stmt gotoOldTargetStmt2 = G.jimple.newGotoStmt(oldTarget);
			System.out.println("Insert before old target: " + gotoOldTargetStmt2.toString());
			((PatchingChain) units).insertBeforeNoRedirect(gotoOldTargetStmt2, oldTarget);
			
			System.out.println("Insert before old target: " + assumeTruStmt.toString());
			((PatchingChain) units).insertBeforeNoRedirect(assumeTruStmt, gotoOldTargetStmt2);
			
			System.out.println("Insert before old target: " + gotoOldTargetStmt1.toString());
			((PatchingChain) units).insertBeforeNoRedirect(gotoOldTargetStmt1, assumeTruStmt);
			
			//Let if-statement jump to "assume: true" assumption 
			ifStmt.setTarget(assumeTruStmt);
		}

        //Insert variable initialization at begin of method to avoid VRFY error
    	for (Value v: toInitialize) {
    		Stmt initStmt = G.jimple.newAssignStmt(v, NullConstant.v());
            units.insertAfter(initStmt, lastParamStmt);
    	}
        Stmt symInitStmt = G.jimple.newAssignStmt(symVar, NullConstant.v());    	
    	units.insertAfter(symInitStmt, lastParamStmt);//#### initialize _sym_tmp_4 = null in the front

	}

	private void insertPrologue(Body body, List<Local> params)//#### body of the whole onClick(); params = [$r0, $r1]
	{
		Chain<Unit> units = body.getUnits().getNonPatchingChain();
		for (Unit u : units) {
			Stmt s = (Stmt) u;//#### for each stmt in the body
			if (paramOrThisIdentityStmt(s)) {//#### param or this
				continue;
			}
			else {//#### insert before the first none-ParamOrThis stmt
				//#### 2016.06.21 symbolize input method: main + instrumentor + InputMethodsHandler + Annotation
				boolean isSymbolic = Annotation.isSymbolicMethod(currentMethod);
				if (isSymbolic) {
					System.out.println("symbolic = " + isSymbolic);
					SootMethod injector = InputMethodsHandler.addInjector(currentMethod);
					List ps = new ArrayList(params);
					if (!currentMethod.isStatic()) {
						ps.remove(0);
					}
					units.insertBefore(G.jimple.newInvokeStmt(G.staticInvokeExpr(injector.makeRef(), ps)), s);
				}
				
				
				Local symArgsArray = G.jimple.newLocal(new String("a3targs$symargs"), ArrayType.v(G.EXPRESSION_TYPE, 1));//#### a3targs$symargs
				body.getLocals().addFirst(symArgsArray);//#### add a3targs$symargs as the first local reg
				int subsigId = methSubsigNumberer.getOrMakeId(currentMethod);
				units.insertBefore(G.jimple.newAssignStmt(symArgsArray, G.staticInvokeExpr(G.argPop,
					IntConstant.v(subsigId), IntConstant.v(sigIdOfCurrentMethod), IntConstant.v(params.size()))), s);
					//#### insert prologue: a3targs$symargs = argPop(0, 0, 2)
					// 0 = subsigId of CurrentMethod(onClick)
					// 0 = sigId of CurrentMethod(onClick)
					// 2 = size of params appeared in ParamStmt
				for(int i = 0; i < params.size(); i++){//#### none of the two params can be symolized
					Local l = params.get(i);
					if(addSymLocationFor(l.getType())) {
						units.insertBefore(G.jimple.newAssignStmt(symLocalfor(l),
																  G.jimple.newArrayRef(symArgsArray,IntConstant.v(i))), s);
					}
				}
				break;//#### insert only once
			}
		}
	}

	private void handleInvokeExpr(Stmt s) {//#### handle stmt: "$l0 = currentTimeMillis();"; deals with both modeled and unmodeled callees
		InvokeExpr ie = s.getInvokeExpr();//#### ie = currentTimeMillis()
		List symArgs = new ArrayList();//#### symArgs initialization: {}
		SootMethod callee = ie.getMethod();//#### callee = ie
		int subSig = methSubsigNumberer.getOrMakeId(callee);//#### currentTimeMillis(): subSig = 1
		
		// Insert TARGET marker, if invocation of target method //#### i.e., dynamic load
		if (TARGET_METHODS.contains(ie.getMethod().getSignature())) {
			Local targetName = G.newLocal(RefType.v("java.lang.String"));
			G.assign(targetName, StringConstant.v(ie.getMethod().getSignature()));
			G.invoke(G.staticInvokeExpr(G.targetHit, targetName));
		}
		
		//pass the subsig of the callee
		symArgs.add(IntConstant.v(subSig));//#### symArgs += {1}
		
		List args = new ArrayList();
		
		Immediate base = null;
		//Handle symbolic base register (if any) 
		if (ie instanceof InstanceInvokeExpr) {//#### true
			base = (Immediate) ((InstanceInvokeExpr) ie).getBase();
			args.add(base);
			symArgs.add(symLocalfor(base));
			//symArgs.add(NullConstant.v());
		}
		
		//Handle symbolic arguments (if any)
		//#### 2016.06.19 record modled method's args, to make them a part of return val's name
		List methArgs = new ArrayList();
		for (Iterator it = ie.getArgs().iterator(); it.hasNext();) {//#### methods time()/network() have no args
			Immediate arg = (Immediate) it.next();
			args.add(arg);
			symArgs.add(addSymLocationFor(arg.getType()) ? symLocalfor(arg) : NullConstant.v());
			methArgs.add(arg);
		}
		//#### insert "argPush(1)" Before; the localVar symArg contains subSig=1, so another param is Expression[0]
		G.invoke(G.staticInvokeExpr(G.argPush[symArgs.size()-1], symArgs));

		if (s instanceof AssignStmt) {
			Local retValue = (Local) ((AssignStmt) s).getLeftOp();//#### retValue is $l0
			if(addSymLocationFor(retValue.getType())) {//#### long is supported
				// Force solution value to drive execution down the new path.
				//#### if method modeled, get 
				SootMethod modelInvoker = ModelMethodsHandler.getModelInvokerFor(callee);
				if (modelInvoker != null) {
					//#### 2016.06.11 presently, we only support modeling methods returning String
					//#### insert "$l0 = getSolution_long($L$sym_java_lang_System_currentTimeMillis__J)" After
					//G.editor.insertStmtAfter(G.jimple.newAssignStmt(retValue, G.staticInvokeExpr(G.getSolution_long, StringConstant.v(toSymbolicVarName(callee)))));
					// modeled method name --> return value' name
					//#### 2016.06.19
					String methRetName = toSymbolicVarName(callee);
					for (Iterator it = methArgs.iterator(); it.hasNext();) {
						Immediate arg = (Immediate) it.next();
						int len = arg.toString().length();
						methRetName += "_";
						methRetName += arg.toString().substring(1, len-1);
					}
					G.editor.insertStmtAfter(G.jimple.newAssignStmt(retValue, G.staticInvokeExpr(G.getSolution_string, StringConstant.v(methRetName))));
				}
				//#### 2016.06.11 
				//#### (1)do not support long; 
				//#### (2)I merged the following two stmts, as retpop was not paired with retpush (need instrument inside the target method, which is not meant by modeled method)
				/*
				//#### insert "$l0$sym = retPop(1)" After
				//#### 1 is subSig of currentTimeMillis()
				G.editor.insertStmtAfter(G.jimple.newAssignStmt(symLocalfor(retValue),
																G.staticInvokeExpr(G.retPop, IntConstant.v(subSig))));
				
				if (modelInvoker != null) {//#### currentTimeMillis____J()
					//#### insert "currentTimeMillis____J();" After
					G.editor.insertStmtAfter(G.jimple.newInvokeStmt(G.staticInvokeExpr(modelInvoker.makeRef(), args)));
				}
				*/
				if (modelInvoker == null) {
				G.editor.insertStmtAfter(G.jimple.newAssignStmt(symLocalfor(retValue),
						G.staticInvokeExpr(G.retPop, IntConstant.v(subSig))));
				} else {
					if (ie instanceof InstanceInvokeExpr) {
						args.remove(base);
					}
					G.editor.insertStmtAfter(G.jimple.newAssignStmt(symLocalfor(retValue),
							G.staticInvokeExpr(modelInvoker.makeRef(), args)));
				}
			}
		}
	}

	/**
	 * Converts Soot method name to symbolic variable representing its return value.
	 * 
	 * @param callee
	 * @return
	 */
	private String toSymbolicVarName(SootMethod callee) {
		String name =callee.getBytecodeSignature();
		name = name.replace(".", "_");
		name = name.replace(": ", "_");
		name = name.replace('(', '_');
		name = name.replace(')', '_');
		name = name.replace(',', '_');
		name = name.replace("<","").replace(">","");
		//#### 2016.06.11 
		//#### TODO: more general replacement is needed
		name = name.replace(';', 'g');
		
		String t = callee.getReturnType().toString();
		if (t.equals("long")) {
			t = "L";
		} else if (t.equals("int")) {
			t = "I";
		} //TODO handle more types.
		//#### 2016.06.11 
		//#### For z3 to recogonize it as String.
		else if (t.equals("java.lang.String")) {
			t = "X";
		}
		
		return "$"+t+"$sym_"+name;
	}
	

	/**
	 * Converts field name to symbolic variable name.
	 * 
	 * @param fld
	 * @return
	 */
	private String toSymbolicVarName(SootField fld) {
		String t = fld.getType().toString();
		if (t.equals("java.lang.String")) {
			t = "X";
		}  else if (t.equals("int")) {
			t = "I";
		} //TODO handle more types
		String name = fld.getSignature();
		name = name.replace('.', '_');
		name = name.replace(':', '_');
		name = name.replace(' ', '_');
		name = name.replace(',', '_');
		name = name.replace("<","").replace(">","");
		return "$"+t+"$sym_"+name;
	}

	

	/**
	 * Called by soot.util.Switchable.apply()
	 */
	@Override
	public void caseAssignStmt(AssignStmt as)
	{
		Value rightOp = as.getRightOp();
		Value leftOp = as.getLeftOp();

		if (rightOp instanceof BinopExpr) {
			handleBinopStmt((Local) leftOp, (BinopExpr) rightOp);
		}
		if (rightOp instanceof NegExpr) {
			handleNegStmt((Local) leftOp, (NegExpr) rightOp);
		}
		else if (leftOp instanceof FieldRef) {
			handleStoreStmt((FieldRef) leftOp, (Immediate) rightOp);
		}
		else if (rightOp instanceof FieldRef) {
			handleLoadStmt((Local) leftOp, (FieldRef) rightOp);
		}
		else if (leftOp instanceof ArrayRef) {
			handleArrayStoreStmt((ArrayRef) leftOp, (Immediate) rightOp);
		}
		else if (rightOp instanceof ArrayRef) {
			handleArrayLoadStmt((Local) leftOp, (ArrayRef) rightOp);
		}
		else if (rightOp instanceof LengthExpr) {
			handleArrayLengthStmt((Local) leftOp, (LengthExpr) rightOp);
		}
		else if (rightOp instanceof InstanceOfExpr) {
			handleInstanceOfStmt((Local) leftOp, (InstanceOfExpr) rightOp);
		}
		else if (rightOp instanceof CastExpr) {
			handleCastExpr((Local) leftOp, (CastExpr) rightOp);
		}
		else if (rightOp instanceof NewExpr) {
			handleNewStmt((Local) leftOp, (NewExpr) rightOp);
		}
		else if (rightOp instanceof NewArrayExpr) {
			handleNewArrayStmt((Local) leftOp, (NewArrayExpr) rightOp);
		}
		else if (rightOp instanceof NewMultiArrayExpr) {
			handleNewMultiArrayStmt((Local) leftOp, (NewMultiArrayExpr) rightOp);
		}
		else if (rightOp instanceof Immediate && leftOp instanceof Local) {
			handleSimpleAssignStmt((Local) leftOp, (Immediate) rightOp);
		} else {
			System.out.println("Unhandled assign stmt: " + as);
		}
	}

	@Override
	public void caseIdentityStmt(IdentityStmt is) {
		if (!(is.getRightOp() instanceof CaughtExceptionRef))
			assert false : "unexpected " + is;
	}

	void handleBinopStmt(Local leftOp, BinopExpr binExpr) {
		Local leftOp_sym = localsMap.get(leftOp);
		Value rightOp_sym = handleBinopExpr(binExpr, false, localsMap);
		if (rightOp_sym == null)
			rightOp_sym = NullConstant.v();
		G.assign(leftOp_sym, rightOp_sym);
	}

	void handleNegStmt(Local leftOp, NegExpr negExpr) {
		Local lefOp_sym = localsMap.get(leftOp);
		Immediate operand = (Immediate) negExpr.getOp();
		Value rightOp_sym;
		if (operand instanceof Constant) {
			rightOp_sym = NullConstant.v();
		} else {
			String methodSig = G.EXPRESSION_CLASS_NAME + " " + G.negMethodName +
				"(" + G.EXPRESSION_CLASS_NAME + ")";
			SootMethodRef ref = G.symOpsClass.getMethod(methodSig).makeRef();
			Local operand_sym = (Local) localsMap.get(operand);
			rightOp_sym = G.staticInvokeExpr(ref, operand_sym);
       	}
		G.assign(lefOp_sym, rightOp_sym);
	}

	void handleSimpleAssignStmt(Local leftOp, Immediate rightOp) {
		if(!addSymLocationFor(leftOp.getType()))
			return;
		G.assign(symLocalfor(leftOp), symLocalfor(rightOp));
		
		// Handle system constant which should be replaced by solutions.
		Local retValue = (Local) leftOp;
	}

	void handleStoreStmt(FieldRef leftOp, Immediate rightOp) {
		Immediate base;
		if (leftOp instanceof StaticFieldRef) {
			base = NullConstant.v();
		} else {
			base = (Local) ((InstanceFieldRef) leftOp).getBase();
		}

		SootField fld = leftOp.getField();
		
		boolean isClassInstrumented = false;
		for (SootMethod m:fld.getDeclaringClass().getMethods()) {
			if (Main.isInstrumented(m)) {
				isClassInstrumented = true;
				break;
			}
		}
		
		//#### this is troublesome!
		if (!isClassInstrumented&&!fld.getDeclaringClass().getName().contains("android.os")) 
			return;

		if(addSymLocationFor(fld.getType()) && fieldsMap.containsKey(fld)) {
			
			SootField fld_sym = fieldsMap.get(fld);
			assert fld_sym != null : "No sym var for " + fld + " " + fld.getDeclaringClass();
			FieldRef leftOp_sym;
			if (leftOp instanceof StaticFieldRef) {
				leftOp_sym = G.staticFieldRef(Scene.v().makeFieldRef(fld.getDeclaringClass(), fld_sym.getName(), fld_sym.getType(), true));
			} else {
				leftOp_sym = G.instanceFieldRef(base, Scene.v().makeFieldRef(fld.getDeclaringClass(), fld_sym.getName(), fld_sym.getType(), false));
			}
			G.assign(leftOp_sym, symLocalfor(rightOp));
		} 

		//Fields defined in application will be considered by symbolic rw/ww ops
		if (doRW(fld)) {
			int fld_id = fieldSigNumberer.getOrMakeId(fld);
			if (rwKind == RWKind.ID_FIELD_WRITE) {
				SootField idFld = idFieldsMap.get(fld);
				if(idFld != null) {
					//#### heap
					//#### odd conflict occurs, so we didn't added idField in addSymbolicFields()
					//if(idFld.getName().equals("balance$a3tid") && !idFld.isDeclared()){
					//if(!mainact.declaresField(idFld.getName(), idFld.getType())){
					if(!idFld.isDeclared() && !fld.getDeclaringClass().declaresField(idFld.getName(), idFld.getType())){
						//mainact.removeField(fld_id);// declares same fld_id (different object), so nothing to remove but cannot add
						fld.getDeclaringClass().addField(idFld);
					}
					
					
					FieldRef leftOp_id, leftOp_id1; 
					if (leftOp instanceof StaticFieldRef) {
						leftOp_id = G.staticFieldRef(idFld.makeRef());
						leftOp_id1 = G.staticFieldRef(idFld.makeRef());
					}
					else {
						leftOp_id = G.instanceFieldRef(base, idFld.makeRef());
						leftOp_id1 = G.instanceFieldRef(base, idFld.makeRef());
					}
					Local tmp = G.newLocal(IntType.v());
					G.assign(tmp, leftOp_id);
					G.invoke(G.staticInvokeExpr(G.ww, tmp, IntConstant.v(fld_id)));
					G.assign(tmp, G.staticInvokeExpr(G.eventId));
					G.assign(leftOp_id1, tmp);
				}
				G.invoke(G.staticInvokeExpr(G.only_write, IntConstant.v(fld_id)));
			} else if (rwKind == RWKind.EXPLICIT_WRITE) {
				G.invoke(G.staticInvokeExpr(G.explicit_write, base, IntConstant.v(fld_id)));
			} else if (rwKind == RWKind.ONLY_WRITE) {
				G.invoke(G.staticInvokeExpr(G.only_write, IntConstant.v(fld_id)));
			}
		}
	}
	
	
	/**
	 * Create symbolic counterpart of fields, even if not part of application.
	 * 
	 * @param fld
	 * @return
	 */
	//#### dynamicly create model class (models.android.os.Build), regardless of whether or not it is used
	private SootField retrieveSymbolicStringField(SootField fld) {
		SootClass c = null;
		String modelledClassName = "models."+fld.getDeclaringClass().getName();
		//#### android.os.Build -> models.android.os.Build (direct mapping)
		
		if (!Scene.v().containsClass(modelledClassName)) {
			c = MethodUtils.createClass(modelledClassName);
		} else {
			c = Scene.v().getSootClass(modelledClassName);
		}
		//#### either create or retrieve the modelled SootClass
		//#### "Warning: models.android.os.Build is a phantom class!" means the class exists or failed to create?
		//#### c is assigned as models.android.os.Build
		
		String fldName = fld.getName()+"$sym";//#### BOARD$sym
		SootField symField = null;
		if (c.declaresFieldByName(fldName)) {
			symField = c.getFieldByName(fldName);
		} else {
			//#### add the field if model didn't declare BOARD$sym: models.android.os.Build: acteve.symbolic.integer.Expression BOARD$sym
			symField = new SootField(fldName, G.EXPRESSION_TYPE, fld.getModifiers());
			c.addField(symField);
		}		
			
		//Assign non-clinit Method value to field TODO Possibly unneeded. Already created by G.createClass()
		//#### static class init?
		SootMethod clinitMethod = null;
		  if (!c.declaresMethod("<clinit>", new ArrayList(), soot.VoidType.v())) {
			//Add static initializer
		    clinitMethod = new soot.SootMethod("<clinit>", new ArrayList(), soot.VoidType.v(), soot.Modifier.STATIC, new ArrayList<SootClass>());                
		    clinitMethod.setActiveBody(Jimple.v().newBody(clinitMethod));
		    c.addMethod(clinitMethod);
		} else {
		    clinitMethod = c.getMethod("<clinit>", new ArrayList(), soot.VoidType.v());
		}		  
		Body initializer = clinitMethod.retrieveActiveBody();
		PatchingChain<Unit> units = initializer.getUnits();

		//Initialize field in static initializer
		//#### BOARD$sym is static
		if (symField.isStatic()) {//#### the created BOARD$sym field corresponding to model sootclass
			
			String fieldTypee = null;
			String fieldSpecificTypee = null;
			String ctorReff = null;
			String newStrCnstStmtt = null;
			List<Value> values = null;
			//####
			switch (fld.getType().toString()){
				case "int":
					fieldTypee = "acteve.symbolic.integer.Expression";
					fieldSpecificTypee = "acteve.symbolic.integer.SymbolicInteger";
					ctorReff = "void <init>(int,java.lang.String,int)";
					newStrCnstStmtt = "acteve.symbolic.integer.SymbolicInteger";
					break;
				case "java.lang.String":
					fieldTypee = "acteve.symbolic.integer.Expression";
					fieldSpecificTypee = "acteve.symbolic.string.SymbolicString";
					ctorReff = "void <init>(java.lang.String)";
					newStrCnstStmtt = "acteve.symbolic.string.SymbolicString";
					break;
				default:
					break;
			}
			
			RefType fieldType = RefType.v(fieldTypee);
			RefType fieldSpecificType = RefType.v(fieldSpecificTypee);
			
			//#### add BOARD$sym initialization to model class
			Local loc = Jimple.v().newLocal(toSymbolicVarName(fld),fieldType);//#### $X$sym_android_os_Build__java_lang_String_BOARD
			initializer.getLocals().add(loc);
			//#### refer to ctor of SymbolicString:  <acteve.symbolic.string.SymbolicString: void <init>(java.lang.String)>
			SootMethodRef ctorRef = fieldSpecificType.getSootClass().getMethod(ctorReff).makeRef();

			//#### BOARD$sym = new SymbolicString("$X$sym_android_os_Build__java_lang_String_BOARD");
			//$X$sym_android_os_Build__java_lang_String_BOARD = new SymbolicString
			AssignStmt newStrCnstStmt = Jimple.v().newAssignStmt(loc, Jimple.v().newNewExpr(RefType.v(newStrCnstStmtt)));
			//specialinvoke $X$sym_android_os_Build__java_lang_String_BOARD.<SymbolicString: void <init>(java.lang.String)>("$X$sym_android_os_Build__java_lang_String_BOARD")
			InvokeStmt invokeCnstrctrStmt = null;
		
			//####
			switch (fld.getType().toString()){
			case "int":
				invokeCnstrctrStmt = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(loc, ctorRef,  IntConstant.v(4),StringConstant.v(toSymbolicVarName(fld)),IntConstant.v(0) ));
				break;
			case "java.lang.String":
				invokeCnstrctrStmt = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(loc, ctorRef, StringConstant.v(toSymbolicVarName(fld))));
				break;
			default:
				break;
			}
			
			//<models.android.os.Build: Expression BOARD$sym> = $X$sym_android_os_Build__java_lang_String_BOARD
			AssignStmt assignToSymFld = Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(symField.makeRef()), loc);
		
			Iterator<Unit> it = units.iterator();
			Unit insertPoint = null;
			if (units.size()>0) {
				while (it.hasNext() && !((insertPoint = it.next()) instanceof JReturnVoidStmt)) { /* fast forward until return ... */ }
			} else {
				insertPoint = Jimple.v().newReturnVoidStmt();
				units.add(insertPoint);
			}
			//#### inser before insertpoint ("return"); these stmts are still ordered
			units.insertBefore(newStrCnstStmt, insertPoint);
			units.insertBefore(invokeCnstrctrStmt, insertPoint);
			units.insertBefore(assignToSymFld, insertPoint);
			/*
			public class Build
			{
			    public static final Expression BOARD$sym;
			    
			    static {
				BOARD$sym = new SymbolicString("$X$sym_android_os_Build__java_lang_String_BOARD");
			    }
			}
			*/
		
		} else {
			log.error("Field is not static " + fld);
		}		
		return symField;
	}
	
	//#### instrumenting: $r3 = <android.os.Build: java.lang.String BOARD>, i.e., tracing rightOp
	void handleLoadStmt(Local leftOp, FieldRef rightOp) 
	{
		Immediate base;
		if (rightOp instanceof StaticFieldRef) {
			base = NullConstant.v();//#### null: the BOARD is static field and statically called
		} else {
			base = (Local) ((InstanceFieldRef) rightOp).getBase();
		}

		SootField fld = rightOp.getField();//#### android.os.Build.BOARD
		Local leftOp_sym = localsMap.get(leftOp);//#### $r3$sym
		boolean isClassInstrumented = false;
		for (SootMethod m:fld.getDeclaringClass().getMethods()) {
			if (Main.isInstrumented(m)) {//#### methods in android.os.Build all not instrumented
				isClassInstrumented = true;
				break;
			}
		}
		if (!isClassInstrumented && !Config.g().fieldsToModel.contains(fld.toString())) {//#### only trace <existing(instrumented) symVar> or <modeled field>; if not instrumented and not modeled, just return  
			if(leftOp_sym != null)
				G.assign(leftOp_sym, NullConstant.v());
			return;
		}
		System.out.println("Tracing field "+fld.toString());
		if(addSymLocationFor(fld.getType())) {//#### String Board is supported, so return true
			
			//#### a) trace previous symbolic field
			//#### b) trace modeled field
			if (!fieldsMap.containsKey(fld)) {//#### instrumenting models.Build (fieldsMap maps fields in models.Build, localMap maps regs in onClick())
				//#### 1. Create symField (instrument models): models.android.os.Build.BOARD$sym
				//#### if fieldsMap contains fld, means fld is ready for nomal symbolic tracing (fieldsMap contains localMap?)
				SootField symField = retrieveSymbolicStringField(fld);// Create symbolic counterpart of fields, even if not part of application
				fieldsMap.put(fld, symField);
			}
			
			SootField fld_sym = fieldsMap.get(fld);//#### models.android.os.Build.BOARD$sym
			assert fld_sym != null : fld + " " + fld.getDeclaringClass();//#### if fld_sym == null, the latter part after ":" will be printed
			FieldRef rightOp_sym;
			if (rightOp instanceof StaticFieldRef) {//#### 2. trace symReg (SE): rightOp(android.os.Build.BOARD) -> rightOp_sym(android.os.Build.BOARD$sym)
				//rightOp_sym = G.staticFieldRef(Scene.v().makeFieldRef(fld.getDeclaringClass(), fld_sym.getName(), fld_sym.getType(), true));
			                                                           // android.os.Build         BOARD$sym           RefType
				//#### If rightField modeled, use model's symFieldDefinition. Thus bypassing sdk-instrumentation.
				/**/
				if (Config.g().fieldsToModel.contains(fld.toString())) {
					rightOp_sym = G.staticFieldRef(Scene.v().makeFieldRef(fld_sym.getDeclaringClass(), fld_sym.getName(), fld_sym.getType(), true));
				} else {
					rightOp_sym = G.staticFieldRef(Scene.v().makeFieldRef(fld.getDeclaringClass(), fld_sym.getName(), fld_sym.getType(), true));
				}
				
			} else {
				rightOp_sym = G.instanceFieldRef(base, Scene.v().makeFieldRef(fld.getDeclaringClass(), fld_sym.getName(), fld_sym.getType(), false));
			}
			G.assign(leftOp_sym, rightOp_sym);//#### create new jimple assignment
		} else if(leftOp_sym != null) {
			G.assign(leftOp_sym, NullConstant.v());
		}

        if (doRW(fld)) {//#### write android.os.Build.Board?
			if (rwKind == RWKind.ID_FIELD_WRITE) {
				SootField fld_id = idFieldsMap.get(fld);//#### null
				if(fld_id != null) {
					//#### heap
					//#### odd conflict occurs, so we didn't added idField in addSymbolicFields()
					//if(fld_id.getName().equals("balance$a3tid") && !fld_id.isDeclared()){
					if(!fld_id.isDeclared() && !fld.getDeclaringClass().declaresField(fld_id.getName(), fld_id.getType())){//main not contain so can add
						//mainact.removeField(fld_id);// declares same fld_id (different object), so nothing to remove but cannot add
						fld.getDeclaringClass().addField(fld_id);
					}
					
					FieldRef rightOp_id;
					if (rightOp instanceof StaticFieldRef)
						rightOp_id = G.staticFieldRef(fld_id.makeRef());
					else
						rightOp_id = G.instanceFieldRef(base, fld_id.makeRef());
					Local tmp = G.newLocal(IntType.v());
					G.assign(tmp, rightOp_id);
					int id = fieldSigNumberer.getOrMakeId(fld);
					G.invoke(G.staticInvokeExpr(G.rw, tmp, IntConstant.v(id)));
					//G.invoke(G.staticInvokeExpr(G.id_field_read, IntConstant.v(id)));
				}
			} else if (rwKind == RWKind.EXPLICIT_WRITE) {
				// TODO
			}
        }

		// 3. inject value for modeled field: "Overwrite"(getSolution) concrete value with solution
        //#### insert Util.getSolution_string
		if (ModelMethodsHandler.modelExistsFor(fld)) { //Modelled?
			/*if (fld.getType() instanceof PrimType) || fld.getType().toString().equals("java.lang.String")) { //Supported type?
				G.editor.insertStmtAfter(Jimple.v().newAssignStmt(leftOp, G.staticInvokeExpr(G.getSolution_string, StringConstant.v(toSymbolicVarName(fld)))));
			} else {                               //assign $r3 with static class method invocation: $r3 = Util.getSolution_string(SymbolicFieldName)
				log.error("Modelled field of non-supported type: " + fld.getName() + " : " + fld.getType());
			}*/
			//####
			switch (fld.getType().toString()){
				case "int":
					G.editor.insertStmtAfter(Jimple.v().newAssignStmt(leftOp, G.staticInvokeExpr(G.getSolution_int, StringConstant.v(toSymbolicVarName(fld)))));
					break;
				case "java.lang.String":
					G.editor.insertStmtAfter(Jimple.v().newAssignStmt(leftOp, G.staticInvokeExpr(G.getSolution_string, StringConstant.v(toSymbolicVarName(fld)))));
					break;
				default:
					log.error("Modelled field of non-supported type: " + fld.getName() + " : " + fld.getType());
			}
		} else {
			log.debug("Not modelled: " + fld.toString());
		}
	}


	void handleNewStmt(Local leftOp, NewExpr rightOp)
	{
		Local leftOp_sym = localsMap.get(leftOp);
		if(leftOp_sym != null)
			G.editor.insertStmtAfter(G.jimple.newAssignStmt(leftOp_sym, NullConstant.v()));
	}

	void handleNewArrayStmt(Local leftOp, NewArrayExpr rightOp)
	{
		Local leftOp_sym = localsMap.get(leftOp);
		if(leftOp_sym != null)
			G.editor.insertStmtAfter(G.jimple.newAssignStmt(leftOp_sym, NullConstant.v()));
	}

	void handleNewMultiArrayStmt(Local leftOp, NewMultiArrayExpr rightOp) {
		//Local leftOp_sym = localsMap.get(leftOp);
		//G.editor.insertStmtAfter(G.jimple.newAssignStmt(leftOp_sym, NullConstant.v()));
	}
	
	@Override
	public void caseReturnStmt(ReturnStmt rs) {
		Immediate retValue = (Immediate) rs.getOp();
		if(!addSymLocationFor(retValue.getType()))
			return;
		int subSig = methSubsigNumberer.getOrMakeId(currentMethod);
		G.invoke(G.staticInvokeExpr(G.retPush, IntConstant.v(subSig), symLocalfor(retValue)));
	}

	public void caseReturnVoidStmt(ReturnStmt rs) {	
	}

	void handleCastExpr(Local leftOp, CastExpr castExpr) {
		if(!addSymLocationFor(leftOp.getType()))
			return;
		Local leftOp_sym = localsMap.get(leftOp);
		Immediate rightOp = (Immediate) castExpr.getOp();
		Type type = castExpr.getCastType();
		if (rightOp instanceof Constant) {
           	G.assign(leftOp_sym, NullConstant.v());
		} else {
			Local op_sym = localsMap.get((Local) rightOp);
			if (op_sym != null) {
				if (type instanceof PrimType) {
					SootMethodRef ref = G.symOpsClass.getMethodByName(G.castMethodName).makeRef();
					Integer t = G.typeMap.get(type);
					if(t == null)
						throw new RuntimeException("unexpected type " + type);
					G.assign(leftOp_sym, G.staticInvokeExpr(ref, op_sym, IntConstant.v(t.intValue())));
				} else {
					//TODO: now sym values corresponding non-primitive types
					//flow through cast operations similar to assignment operation
					G.assign(leftOp_sym, op_sym);
				}
			}
		}
	}

	void handleArrayLoadStmt(Local leftOp, ArrayRef rightOp) {
		Local base = (Local) rightOp.getBase();
		Immediate index = (Immediate) rightOp.getIndex();
		
		Local base_sym = localsMap.get(base);
		Local leftOp_sym = localsMap.get(leftOp);
		if(base_sym != null) {
			Immediate index_sym = index instanceof Constant ? NullConstant.v() : localsMap.get((Local) index);
			Type[] paramTypes = new Type[]{G.EXPRESSION_TYPE, G.EXPRESSION_TYPE, base.getType(), IntType.v()};
			SootMethodRef ref = G.symOpsClass.getMethod(G.arrayGetMethodName, Arrays.asList(paramTypes)).makeRef();
			G.assign(leftOp_sym, G.staticInvokeExpr(ref, Arrays.asList(new Immediate[]{base_sym, index_sym, base, index})));
		} else if(leftOp_sym != null){
			G.assign(leftOp_sym, NullConstant.v());
		}
		if (doRW()) {
			if (rwKind == RWKind.ID_FIELD_WRITE || rwKind == RWKind.EXPLICIT_WRITE)
				G.invoke(G.staticInvokeExpr(G.readArray, base, index));
        }
	}

	void handleArrayLengthStmt(Local leftOp, LengthExpr rightOp) {
		Local leftOp_sym = localsMap.get(leftOp);
		Local base = (Local) rightOp.getOp();
		if(addSymLocationFor(base.getType())){
			Local base_sym = localsMap.get(base);
			SootMethodRef ref = G.symOpsClass.getMethodByName(G.arrayLenMethodName).makeRef();
			G.assign(leftOp_sym, G.staticInvokeExpr(ref, base_sym));
		} else {
			G.assign(leftOp_sym, NullConstant.v());
		}
	}

	void handleArrayStoreStmt(ArrayRef leftOp, Immediate rightOp)
	{
		Local base = (Local) leftOp.getBase();
		Immediate index = (Immediate) leftOp.getIndex();

		Local base_sym = localsMap.get(base);
		if(base_sym != null){
			Immediate index_sym = index instanceof Constant ? NullConstant.v() : localsMap.get((Local) index);
			
			Immediate rightOp_sym = rightOp instanceof Constant ? NullConstant.v() : localsMap.get((Local) rightOp);
			
			Type[] paramTypes = new Type[]{G.EXPRESSION_TYPE, G.EXPRESSION_TYPE, G.EXPRESSION_TYPE,
										   base.getType(), IntType.v(), ((ArrayType) base.getType()).baseType};
			SootMethodRef ref = G.symOpsClass.getMethod(G.arraySetMethodName, Arrays.asList(paramTypes)).makeRef();
			G.invoke(G.staticInvokeExpr(ref, Arrays.asList(new Immediate[]{base_sym, index_sym,
																		   rightOp_sym, base, index, rightOp})));
		}
		if (doRW()) {
			if (rwKind == RWKind.ID_FIELD_WRITE || rwKind == RWKind.EXPLICIT_WRITE)
            	G.invoke(G.staticInvokeExpr(G.writeArray, base, index));
			else if (rwKind == RWKind.ONLY_WRITE)
            	G.invoke(G.staticInvokeExpr(G.only_write, IntConstant.v(-1)));
        }
	}

	void handleInstanceOfStmt(Local leftOp, InstanceOfExpr expr) {
		Local leftOp_sym = localsMap.get(leftOp);
		if(leftOp_sym != null)
			G.assign(leftOp_sym, NullConstant.v());
	}

	/**
	 * Create symbolic counterparts for (some) registers in a method's body.
	 * 
	 * @param body
	 */
	private void addSymLocals(Body body) {
		Chain<Local> locals = body.getLocals();//#### locals declared in this Body: $r0, $r1, $r2, $r3, $z0, $r4, $r5, $r6, $r7, $r8
		Iterator lIt = locals.snapshotIterator();//#### a copy of this chain, avoiding ConcurrentModificationExceptions 
		while (lIt.hasNext()) {
			Local local = (Local) lIt.next();
			if(!addSymLocationFor(local.getType()))//#### $r3 is string, need add $r3$Sym
				continue;
			Local newLocal = G.newLocal(G.EXPRESSION_TYPE, local.getName()+"$sym");//#### $r3$sym
			localsMap.put(local, newLocal);//#### shadow mem: key=$r3 -> value=$r3$sym
		}
	}
	
	/**
	 * Returns <code>true</code> if the requested <code>type</code> should be represented by a symbolic counterpart.
	 *  
	 * @param type
	 * @return
	 */
	private boolean addSymLocationFor(Type type) {
		if(type instanceof PrimType)
			return true;
		if(type instanceof ArrayType){
			ArrayType atype = (ArrayType) type;
			return atype.numDimensions == 1 && atype.baseType instanceof PrimType;
		}
		if(type instanceof RefType){
			if(type.equals(G.OBJECT_TYPE))
				return true;
			String className = ((RefType) type).getSootClass().getName();
			if(className.equals("java.io.Serializable") ||
			   className.equals("java.lang.Cloneable")
			   ||  className.equals("java.lang.String")
			   )
				return true;
		}
		return false; //because arrays are subtypes of object
	}
	
	/**
	 * Returns the symbolic counterpart of a register.
	 * @param v
	 * @return
	 */
	private Immediate symLocalfor(Immediate v) {
		if (v instanceof Constant)
			return NullConstant.v();
		else {
			Local l = localsMap.get((Local) v);
			return l == null ? NullConstant.v() : l;
		}
	}
	
	public static boolean paramOrThisIdentityStmt(Stmt s) {
		if (!(s instanceof IdentityStmt))
			return false;
		return !(((IdentityStmt) s).getRightOp() instanceof CaughtExceptionRef);
	}

    private Value handleBinopExpr(BinopExpr binExpr, boolean negate, Map<Local,Local> localsMap) {
		Immediate op1 = (Immediate) binExpr.getOp1();
        Immediate op2 = (Immediate) binExpr.getOp2();
		
		String binExprSymbol = binExpr.getSymbol().trim();
		if (negate) {
			binExprSymbol = G.negationMap.get(binExprSymbol);
		}
		if (op1 instanceof Constant && op2 instanceof Constant) {
			return null;
		}

		Type op1Type = op1.getType();
		op1Type = op1Type instanceof RefLikeType ? RefType.v("java.lang.Object") : Type.toMachineType(op1Type);

		Type op2Type = op2.getType();
		op2Type = op2Type instanceof RefLikeType ? RefType.v("java.lang.Object") : Type.toMachineType(op2Type);

		Immediate symOp1 = op1 instanceof Constant || op1==null ? NullConstant.v() : localsMap.get((Local) op1);
		Immediate symOp2 = op2 instanceof Constant || op2==null ? NullConstant.v() : localsMap.get((Local) op2);
		
		//TODO There are no symbolics for fields at this point.
		if (symOp1==null)
			symOp1 = NullConstant.v();
		if (symOp2==null)
			symOp2 = NullConstant.v();
		
		String methodName = G.binopSymbolToMethodName.get(binExprSymbol);
		String methodSig = G.EXPRESSION_CLASS_NAME + " " + methodName + "(" + G.EXPRESSION_CLASS_NAME + "," + 
				G.EXPRESSION_CLASS_NAME + "," + op1Type + "," + op2Type + ")";
		SootMethodRef ref = G.symOpsClass.getMethod(methodSig).makeRef();
		return G.staticInvokeExpr(ref, Arrays.asList(new Immediate[]{symOp1, symOp2, op1, op2}));
    }


	private static final String CONDMAP_FILENAME = "condmap.txt";
	private static final String WRITEMAP_FILENAME = "writemap.txt";
	private static final String METH_SUBSIGS_FILENAME = "methsubsigs.txt";
	private static final String METH_SIGS_FILENAME = "methsigs.txt";
	private static final String FIELD_SIGS_FILENAME = "fieldsigs.txt";

	private void loadFiles() {
		if (sdkDir == null)
			return;
		methSubsigNumberer.load(sdkDir + "/" + METH_SUBSIGS_FILENAME);
		methSigNumberer.load(sdkDir + "/" + METH_SIGS_FILENAME);
		fieldSigNumberer.load(sdkDir + "/" + FIELD_SIGS_FILENAME);
		try {
			BufferedReader in = new BufferedReader(new FileReader(sdkDir + "/" + CONDMAP_FILENAME));
			String s;
			while ((s = in.readLine()) != null)
				condIdStrList.add(s);//#### each item in condmap.txt will become an item in condIdStrList 
			in.close();
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}

	private void saveFiles() {
		methSubsigNumberer.save(outDir + "/" + METH_SUBSIGS_FILENAME);
		methSigNumberer.save(outDir + "/" + METH_SIGS_FILENAME);
		fieldSigNumberer.save(outDir + "/" + FIELD_SIGS_FILENAME);
        try {
            PrintWriter out;

            out = new PrintWriter(new File(outDir + "/" + CONDMAP_FILENAME));
            for (int i = 0; i < condIdStrList.size(); i++) {
                String s = condIdStrList.get(i);
                out.println(s);
            }
            out.close();
        } catch (IOException ex) {
            log.error(ex.getMessage(),ex);
            System.exit(1);
        }
    }
	
	private List<Unit> generateUseDefChain(Body body, Unit u, Local l) {
		UnitGraph unitGraph = new BriefUnitGraph(body);
		SimpleLocalDefs simpleLocalDefs = new SimpleLocalDefs(unitGraph);
		List<Unit> defUnits = simpleLocalDefs.getDefsOfAt(l, u);
		return defUnits;
	}
}

