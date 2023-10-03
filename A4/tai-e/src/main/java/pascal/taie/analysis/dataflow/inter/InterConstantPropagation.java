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

package pascal.taie.analysis.dataflow.inter;

import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.icfg.CallEdge;
import pascal.taie.analysis.graph.icfg.CallToReturnEdge;
import pascal.taie.analysis.graph.icfg.NormalEdge;
import pascal.taie.analysis.graph.icfg.ReturnEdge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;

import java.util.List;

/**
 * Implementation of interprocedural constant propagation for int values.
 */
public class InterConstantPropagation extends
        AbstractInterDataflowAnalysis<JMethod, Stmt, CPFact> {

    public static final String ID = "inter-constprop";

    private final ConstantPropagation cp;

    public InterConstantPropagation(AnalysisConfig config) {
        super(config);
        cp = new ConstantPropagation(new AnalysisConfig(ConstantPropagation.ID));
    }

    @Override
    public boolean isForward() {
        return cp.isForward();
    }

    @Override
    public CPFact newBoundaryFact(Stmt boundary) {
        IR ir = icfg.getContainingMethodOf(boundary).getIR();
        return cp.newBoundaryFact(ir.getResult(CFGBuilder.ID));
    }

    @Override
    public CPFact newInitialFact() {
        return cp.newInitialFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        cp.meetInto(fact, target);
    }

    @Override
    protected boolean transferCallNode(Stmt stmt, CPFact in, CPFact out) {
        // TODO - finish me
        //非赋值语句，直接copy处理
        if(!in.equals(out)){
            out.copyFrom(in);
            return true;
        }
        return false;
    }

    @Override
    protected boolean transferNonCallNode(Stmt stmt, CPFact in, CPFact out) {
        // TODO - finish me
        return cp.transferNode(stmt,in,out);
    }

    @Override
    protected CPFact transferNormalEdge(NormalEdge<Stmt> edge, CPFact out) {
        // TODO - finish me
        //这种边一般是与过程间调用无关的边，edge transfer 函数不需要对此进行特殊的处理
        //transferEdge(edge, fact) = fact
        return out.copy();
    }

    @Override
    protected CPFact transferCallToReturnEdge(CallToReturnEdge<Stmt> edge, CPFact out) {
        // TODO - finish me
        //kill b, 防止meetinto时错误
        CPFact fact = out.copy();
        Stmt stmt = edge.getSource();
        if(stmt instanceof DefinitionStmt<?,?> definitionStmt){
            var lv = definitionStmt.getLValue();
            if(lv instanceof Var v){
                fact.remove(v);
            }
        }
        return fact;
    }

    @Override
    protected CPFact transferCallEdge(CallEdge<Stmt> edge, CPFact callSiteOut) {
        // TODO - finish me
        //将实参（argument）在调用点中的值传递给被调用函数的形参（parameter）
        JMethod method = edge.getCallee();
        List<Var> params = method.getIR().getParams();

        Stmt source = edge.getSource();
        //首先从调用点的 OUT fact 中获取实参的值
        CPFact fact = newInitialFact();
        Invoke invoke = (Invoke) source;
        //然后返回一个新的 fact，这个 fact 把形参映射到它对应的实参的值
        for(int i = 0 ; i< params.size();i++){
            fact.update(params.get(i),callSiteOut.get(invoke.getInvokeExp().getArg(i)));
        }

        return fact;
    }

    @Override
    protected CPFact transferReturnEdge(ReturnEdge<Stmt> edge, CPFact returnOut) {
        // TODO - finish me
        //它从被调用方法的 exit 节点的 OUT fact 中获取返回值（可能有多个，你需要思考一下该怎么处理），然后返回一个将调用点等号左侧的变量映射到返回值的 fact。
        // 举个例子，在图1中，transferEdge(6→3, {x=6,y=7}) = {b=7}。此时，edge transfer 函数返回的结果应该仅包含调用点等号左侧变量的值（例如图1在第三条语句处的b）。

        CPFact fact = newInitialFact();
        var lv_ = edge.getCallSite().getDef();
        // 如果该调用点等号左侧没有变量，那么 edge transfer 函数仅会返回一个空 fact。
        if(lv_.isEmpty())
            return fact;

        //如果有多个return语句，要先看这几个return语句的返回值是否确定统一，如果不统一，就冲突不行
        Value v_ = Value.getUndef();
        for(Var var : edge.getReturnVars()){
            v_ = cp.meetValue(v_,returnOut.get(var));
        }

        //update
        if(edge.getCallSite() instanceof Invoke invoke){
            fact.update(invoke.getLValue(),v_);
        }
        return fact;
    }
}
