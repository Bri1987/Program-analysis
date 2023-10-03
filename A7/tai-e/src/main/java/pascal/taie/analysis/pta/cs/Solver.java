/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.cs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.CSCallGraph;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private PointerAnalysisResult result;

    Solver(AnalysisOptions options, HeapModel heapModel,
           ContextSelector contextSelector) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
    }

    void solve() {
        initialize();
        analyze();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        // process program entry, i.e., main method
        Context defContext = contextSelector.getEmptyContext();
        JMethod main = World.get().getMainMethod();
        CSMethod csMethod = csManager.getCSMethod(defContext, main);
        callGraph.addEntryMethod(csMethod);
        addReachable(csMethod);
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void addReachable(CSMethod csMethod) {
        StmtProcessor stmtProcessor = new StmtProcessor(csMethod);
        // TODO - finish me
        // 上下文的话应该不能这样了,应该还是可以，因为csMethod带了上下文
        if(!callGraph.contains(csMethod)){
            callGraph.addReachableMethod(csMethod);
            csMethod.getMethod().getIR().getStmts().forEach(stmt -> stmt.accept(stmtProcessor));
        }
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {

        private final CSMethod csMethod;

        private final Context context;

        private StmtProcessor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        // TODO - if you choose to implement addReachable()
        //  via visitor pattern, then finish me
        @Override
        public Void visit(New stmt){
            Obj obj = heapModel.getObj(stmt);
            Context context1 = contextSelector.selectHeapContext(csMethod,obj);
            // 调试一下CSVar怎么走的
            workList.addEntry(csManager.getCSVar(context,stmt.getLValue()),PointsToSetFactory.make(csManager.getCSObj(context1,obj)));
            return null;
        }

        @Override
        public Void visit(Copy stmt){
            addPFGEdge(csManager.getCSVar(context,stmt.getRValue()),csManager.getCSVar(context,stmt.getLValue()));
            return null;
        }

        @Override
        public Void visit(LoadField stmt){
            if(stmt.isStatic()){
                //x = Y.f
                addPFGEdge(csManager.getStaticField(stmt.getFieldRef().resolve()),csManager.getCSVar(context,stmt.getLValue()));
            }
            return null;
        }

        @Override
        public Void visit(StoreField stmt){
            if(stmt.isStatic()){
                //Y.f = x
                addPFGEdge(csManager.getCSVar(context,stmt.getRValue()),csManager.getStaticField(stmt.getFieldRef().resolve()));
            }
            return null;
        }

        @Override
        public Void visit(Invoke callsite){
            if(callsite.isStatic()){
                JMethod method = resolveCallee(null,callsite);
                Context method_context = contextSelector.selectContext(csManager.getCSCallSite(context,callsite),method);
                CSMethod csMethod_ = csManager.getCSMethod(method_context,method);
                processSingleCall(csManager.getCSCallSite(context,callsite),csMethod_);
            }
            return null;
        }
    }


    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        // TODO - finish me
        boolean have = pointerFlowGraph.addEdge(source,target);
        if(have){
            if(!source.getPointsToSet().isEmpty()){
                workList.addEntry(target,source.getPointsToSet());
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        // TODO - finish me
        while (!workList.isEmpty()){
            //remove
            var entry = workList.pollEntry();
            //propagate
            PointsToSet subSet = propagate(entry.pointer(),entry.pointsToSet());

            //load store
            if(entry.pointer() instanceof CSVar csVar){
                Var var = csVar.getVar();
                Context context = csVar.getContext();
                for(CSObj obj : subSet){
                    //x.f = y
                    for(StoreField storeField : var.getStoreFields()){
                        if(!storeField.isStatic())
                            addPFGEdge(csManager.getCSVar(context,storeField.getRValue()),csManager.getInstanceField(obj,storeField.getFieldRef().resolve()));
                    }
                    //y = x.f
                    for(LoadField loadField : var.getLoadFields()){
                        if(!loadField.isStatic())
                            addPFGEdge(csManager.getInstanceField(obj,loadField.getFieldRef().resolve()),csManager.getCSVar(context,loadField.getLValue()));
                    }
                    //store array x[i] = y
                    for(StoreArray storeArray : var.getStoreArrays()){
                        addPFGEdge(csManager.getCSVar(context,storeArray.getRValue()),csManager.getArrayIndex(obj));
                    }
                    //load array y = x[i]
                    for(LoadArray loadArray : var.getLoadArrays()){
                        addPFGEdge(csManager.getArrayIndex(obj), csManager.getCSVar(context,loadArray.getLValue()));
                    }
                    //call (not static)
                    processCall(csVar,obj);
                }
            }

        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        // TODO - finish me
        //1. 首先计算pts - pt(n)的差值
        PointsToSet curSet = pointer.getPointsToSet();
        PointsToSet subSet = PointsToSetFactory.make();
        for(var obj : pointsToSet){
            if(!curSet.contains(obj))
                subSet.addObject(obj);
        }

        //2. propagate
        if(!subSet.isEmpty()){
            //union
            for(var obj  : subSet){
                curSet.addObject(obj);
            }
            //succ
            for(var succ : pointerFlowGraph.getSuccsOf(pointer)){
                // 记录，肯定是用差集啊
                workList.addEntry(succ,subSet);
            }
        }
        return subSet;
    }

    private void processSingleCall(CSCallSite csCallsite, CSMethod method) {
        //TODO 记录，(感觉有上下文的话，又可以直接用contain判断了) x，比如two type的l14和l16, context是相同的，但是调用的csCallsite肯定是不同的(一个14一个16)，要分开加边
//        if(callGraph.contains(method) && !callGraph.getCalleesOf(csCallsite).contains(method)){
//            var set = callGraph.getCalleesOf(csCallsite);
//            System.out.println("here");
//        }
//        if(!callGraph.contains(method)){
        if(!callGraph.getCalleesOf(csCallsite).contains(method)) {
            //给这个调用加边
            callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(csCallsite.getCallSite()),csCallsite,method));
            addReachable(method);
            //params
            for(int i = 0; i <method.getMethod().getParamCount(); i++){
                addPFGEdge(csManager.getCSVar(csCallsite.getContext(),csCallsite.getCallSite().getInvokeExp().getArg(i)),
                        csManager.getCSVar(method.getContext(),method.getMethod().getIR().getParam(i)));
            }

            //return
            if(csCallsite.getCallSite().getLValue() != null){
                for(Var returnVar : method.getMethod().getIR().getReturnVars()){
                    addPFGEdge(csManager.getCSVar(method.getContext(), returnVar),csManager.getCSVar(csCallsite.getContext(), csCallsite.getCallSite().getLValue()));
                }
            }
        }
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param recv    the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        // TODO - finish me
        for(Invoke callsite : recv.getVar().getInvokes()){
            //先用caller的context
            CSCallSite csCallSite = csManager.getCSCallSite(recv.getContext(), callsite);
            // 记录，为什么要recvObj，因为静态方法这里传null
            //dispatch
            JMethod method = resolveCallee(recvObj,callsite);
            //select
            Context calleeContext = contextSelector.selectContext(csCallSite, recvObj, method);
            CSMethod csCallee = csManager.getCSMethod(calleeContext, method);
            //this
            workList.addEntry(csManager.getCSVar(csCallee.getContext(), method.getIR().getThis()), PointsToSetFactory.make(recvObj) );
            processSingleCall(csCallSite, csCallee);
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv the receiver object of the method call. If the callSite
     *             is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}