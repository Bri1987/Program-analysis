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

package pascal.taie.analysis.pta.plugin.taint;

import jas.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.cs.Solver;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class TaintAnalysiss {

    private static final Logger logger = LogManager.getLogger(TaintAnalysiss.class);

    private final TaintManager manager;

    private final TaintConfig config;

    private final Solver solver;

    private final CSManager csManager;

    private final Context emptyContext;

    public TaintAnalysiss(Solver solver) {
        manager = new TaintManager();
        this.solver = solver;
        csManager = solver.getCSManager();
        emptyContext = solver.getContextSelector().getEmptyContext();
        config = TaintConfig.readConfig(
                solver.getOptions().getString("taint-config"),
                World.get().getClassHierarchy(),
                World.get().getTypeSystem());
        logger.info(config);
    }

    // TODO - finish me

    public void onFinish() {
        Set<TaintFlow> taintFlows = collectTaintFlows();
        solver.getResult().storeResult(getClass().getName(), taintFlows);
    }

    public Obj handleSource(Invoke callsite, JMethod callee){
        //注意container是这个callsite所在的函数
        Type type = callee.getReturnType();
        Source source = new Source(callee, type);
        //如果在配置文件中确实这是个污点source，在程序中把它标记为taint
        if(config.getSources().contains(source)){
            return manager.makeTaint(callsite, type);
        }
        return null;
    }

    public HashSet<Pair<Var,Obj>> handleTaintTransfers(CSCallSite csCallSite, JMethod callee, CSVar base){
        PointerAnalysisResult pta = solver.getResult();
        //result
        Var result = csCallSite.getCallSite().getLValue();
        HashSet<Pair<Var,Obj>> set = new HashSet<>();

        //先造出taintTransfer
        TaintTransfer taintTransfer;

        //一个callsite可能不止一种情况
        if(base != null){
            //1. base to result
            if(result != null){
                taintTransfer = new TaintTransfer(callee, TaintTransfer.BASE, TaintTransfer.RESULT, result.getType());
                if(config.getTransfers().contains(taintTransfer)){
                    for(CSObj obj : pta.getPointsToSet(base)){
                        if(manager.isTaint(obj.getObject())){
                            set.add(new Pair<>(result,manager.makeTaint(manager.getSourceCall(obj.getObject()), result.getType())));
                        }
                    }
                }
            }
            //2. arg to base
            for(int i = 0; i < csCallSite.getCallSite().getInvokeExp().getArgCount(); i++){
                Var arg = csCallSite.getCallSite().getInvokeExp().getArg(i);
                taintTransfer = new TaintTransfer(callee,i,TaintTransfer.BASE, base.getType());
                if(config.getTransfers().contains(taintTransfer)){
                    for(CSObj obj : pta.getPointsToSet(csManager.getCSVar(csCallSite.getContext(), arg))){
                        if(manager.isTaint(obj.getObject())){
                            set.add(new Pair<>(base.getVar(),manager.makeTaint(manager.getSourceCall(obj.getObject()), base.getType())));
                        }
                    }
                }
            }
        }

        //3. static
        //arg to result, 如果result也没有就不用看了吧
        if(result != null){
            //遍历传入的参数，看有没有taint
            for(int i = 0; i < csCallSite.getCallSite().getInvokeExp().getArgCount(); i++){
                Var arg = csCallSite.getCallSite().getInvokeExp().getArg(i);
                taintTransfer = new TaintTransfer(callee,i, TaintTransfer.RESULT, result.getType());
                if(config.getTransfers().contains(taintTransfer)){
                    for(CSObj obj : pta.getPointsToSet(csManager.getCSVar(csCallSite.getContext(), arg))){
                        if(manager.isTaint(obj.getObject())){
                            set.add(new Pair<>(result, manager.makeTaint(manager.getSourceCall(obj.getObject()), result.getType())));
                        }
                    }
                }
            }
        }

        return set;
    }

    public boolean isTaint(Obj obj) {
        return manager.isTaint(obj);
    }

    private Set<TaintFlow> collectTaintFlows() {
        Set<TaintFlow> taintFlows = new TreeSet<>();
        PointerAnalysisResult result = solver.getResult();
        // TODO - finish me
        // You could query pointer analysis results you need via variable result.

        CallGraph<CSCallSite, CSMethod> callGraph = result.getCSCallGraph();

        //处理sink
        //TODO 记录，不能这样，这样找不到csMethod的context
//        for(Sink sink : config.getSinks()){
//            JMethod method = sink.method();
//            CSMethod csMethod = csManager.getCSMethod(emptyContext,method);
//            callGraph.getCallersOf(csMethod).forEach(csCallSite -> {
//                Var var = method.getIR().getParam(sink.index());
//                for(CSObj obj : result.getPointsToSet(csManager.getCSVar(csCallSite.getContext(),var))){
//                    if(manager.isTaint(obj.getObject())){
//                        taintFlows.add(new TaintFlow(manager.getSourceCall(obj.getObject()), csCallSite.getCallSite() , sink.index()));
//                    }
//                }
//            });
//        }

        callGraph.reachableMethods().forEach(csMethod -> {
            callGraph.getCallersOf(csMethod).forEach(csCallSite -> {
                Invoke callSite = csCallSite.getCallSite();
                JMethod callee = csMethod.getMethod();
                for(int i = 0;i < callSite.getInvokeExp().getArgCount();i ++){
                    Var arg = callSite.getInvokeExp().getArgs().get(i);
                    Sink sink = new Sink(callee, i);
                    if(config.getSinks().contains(sink)){
                        for(Obj obj : result.getPointsToSet(arg)){
                            if(manager.isTaint(obj)){
                                taintFlows.add(new TaintFlow(manager.getSourceCall(obj), callSite, i));
                            }
                        }
                    }
                }
            });
        });
        return taintFlows;
    }
}
