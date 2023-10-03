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

package pascal.taie.analysis.pta.ci;

import jdk.jshell.Snippet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.VoidType;
import pascal.taie.util.AnalysisException;
import pascal.taie.language.type.Type;

import java.util.Collections;
import java.util.List;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        // TODO - finish me
        if(!callGraph.contains(method)){
            callGraph.addReachableMethod(method);
            method.getIR().getStmts().forEach(stmt -> stmt.accept(stmtProcessor));
        }
    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {
        // TODO - if you choose to implement addReachable()
        //  via visitor pattern, then finish me
        @Override
        public Void visit(New newStmt){
            Obj obj = heapModel.getObj(newStmt);
            workList.addEntry(pointerFlowGraph.getVarPtr(newStmt.getLValue()),new PointsToSet(obj));
            return null;
        }

        @Override
        public Void visit(Copy copyStmt){
            addPFGEdge(pointerFlowGraph.getVarPtr(copyStmt.getRValue()),pointerFlowGraph.getVarPtr(copyStmt.getLValue()));
            return null;
        }

        @Override
        public Void visit(StoreField storeField){
            if(storeField.isStatic()){
                JField jField = storeField.getFieldRef().resolve();
                addPFGEdge(pointerFlowGraph.getVarPtr(storeField.getRValue()),pointerFlowGraph.getStaticField(jField));
            }
            return null;
        }

        @Override
        public Void visit(LoadField loadField){
            if(loadField.isStatic()){
                JField jField = loadField.getFieldRef().resolve();
                addPFGEdge(pointerFlowGraph.getStaticField(jField),pointerFlowGraph.getVarPtr(loadField.getLValue()));
            }
            return null;
        }

        // 记录，记得处理静态call,static call只在这里处理，因为后面process call处理不到static call
        @Override
        public Void visit(Invoke callsite){
            if(callsite.isStatic()){
                //拿到method, static method没有obj
                JMethod method = resolveCallee(null,callsite);
                processOneCall(callsite,method);
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
            if(entry.pointer() instanceof VarPtr varPtr){
                Var var = varPtr.getVar();
                for(Obj obj : subSet){
                    //x.f = y
                    for(StoreField storeField : var.getStoreFields()){
                        if(!storeField.isStatic())
                            addPFGEdge(pointerFlowGraph.getVarPtr(storeField.getRValue()),pointerFlowGraph.getInstanceField(obj,storeField.getFieldRef().resolve()));
                    }
                    //y = x.f
                    for(LoadField loadField : var.getLoadFields()){
                        if(!loadField.isStatic())
                            addPFGEdge(pointerFlowGraph.getInstanceField(obj,loadField.getFieldRef().resolve()),pointerFlowGraph.getVarPtr(loadField.getLValue()));
                    }
                    //store array x[i] = y
                    for(StoreArray storeArray : var.getStoreArrays()){
                        addPFGEdge(pointerFlowGraph.getVarPtr(storeArray.getRValue()),pointerFlowGraph.getArrayIndex(obj));
                    }
                    //load array y = x[i]
                    for(LoadArray loadArray : var.getLoadArrays()){
                        addPFGEdge(pointerFlowGraph.getArrayIndex(obj), pointerFlowGraph.getVarPtr(loadArray.getLValue()));
                    }
                    //call (not static)
                    processCall(var,obj);
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
        PointsToSet subSet = new PointsToSet();
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

    private void processOneCall(Invoke callsite, JMethod method){
        // 记录，不能用callGraph.contains(method), 体现不出调用关系
        //这里目前还是上下文不敏感:两个c.m()的话只会进去一次
        if(!callGraph.getCalleesOf(callsite).contains(method)){
            callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(callsite),callsite,method));
            addReachable(method);
            //params
            for(int i = 0 ; i< method.getParamCount(); i++){
                addPFGEdge(pointerFlowGraph.getVarPtr(callsite.getInvokeExp().getArg(i)),pointerFlowGraph.getVarPtr(method.getIR().getParam(i)));
            }

            //return
            // 记录，注意返回类型为空的话不处理
            // 记录，还不能用返回类型不为空处理，要看有没有接收左值
//            if(!callsite.getMethodRef().getReturnType().equals(VoidType.VOID)){
            if(callsite.getLValue() != null) {
                // 记录，还要处理多返回语句
                for(Var returnVar : method.getIR().getReturnVars()){
                    addPFGEdge(pointerFlowGraph.getVarPtr(returnVar),pointerFlowGraph.getVarPtr(callsite.getLValue()));
                }
            }
        }
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        // TODO - finish me
        for(Invoke callsite : var.getInvokes()){
            JMethod method = resolveCallee(recv,callsite);
            //add m this to WL
            //对于this来说，肯定是第一次，直接new PointsToSet即可
            workList.addEntry(pointerFlowGraph.getVarPtr(method.getIR().getThis()),new PointsToSet(recv));
            processOneCall(callsite,method);
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv     the receiver object of the method call. If the callSite
     *                 is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
