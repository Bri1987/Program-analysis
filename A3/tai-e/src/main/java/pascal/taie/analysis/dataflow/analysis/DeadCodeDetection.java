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

package pascal.taie.analysis.dataflow.analysis;

import pascal.taie.analysis.MethodAnalysis;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.Edge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.*;

import java.util.*;
import java.util.concurrent.locks.Condition;

public class DeadCodeDetection extends MethodAnalysis {

    public static final String ID = "deadcode";

    public DeadCodeDetection(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Set<Stmt> analyze(IR ir) {
        // obtain CFG
        CFG<Stmt> cfg = ir.getResult(CFGBuilder.ID);
        // obtain result of constant propagation
        DataflowResult<Stmt, CPFact> constants =
                ir.getResult(ConstantPropagation.ID);
        // obtain result of live variable analysis
        DataflowResult<Stmt, SetFact<Var>> liveVars =
                ir.getResult(LiveVariableAnalysis.ID);
        // keep statements (dead code) sorted in the resulting set
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        // TODO - finish me
        // Your task is to recognize dead code in ir and add it to deadCode

        Set<Stmt> liveCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        // 记录，用队列保证遍历顺序
        Queue<Stmt> stmtQueue = new LinkedList<>();
        //从入口开始遍历cfg
        stmtQueue.add(cfg.getEntry());

        //不可达代码
        while (!stmtQueue.isEmpty()){
            Stmt cur = stmtQueue.poll();

            if(liveCode.contains(cur))
                continue;

            // 1. 控制流不可达标记
            // 2. 分支不可达
            if(!cfg.isExit(cur)){
                if(cur instanceof If ifStmt){
                    //为什么都要live,因为比如if(x>y) goto 4是一句stmt
                    liveCode.add(cur);
                    ConditionExp condition = ifStmt.getCondition();
                    Value cond_ = ConstantPropagation.evaluate(condition, constants.getInFact(cur));
                    Set<Edge<Stmt>> edges = cfg.getOutEdgesOf(cur);

                    if(cond_.isConstant()){
                        boolean cond = cond_.getConstant() != 0;
                        for(var edge : edges){
                            //走不到的就不处理了
                            if((edge.getKind() == Edge.Kind.IF_TRUE && cond) || (edge.getKind() == Edge.Kind.IF_FALSE && !cond)){
                                stmtQueue.add(edge.getTarget());
                                break;
                            }
                        }
                    } else {
                        //记录，别用getTargets那个函数
                        stmtQueue.addAll(cfg.getSuccsOf(cur));
                    }
                }
                else if(cur instanceof SwitchStmt switchStmt){
                    liveCode.add(cur);
                    // var也是exp
                    Value condition = ConstantPropagation.evaluate(switchStmt.getVar(),constants.getInFact(cur));
                    Set<Edge<Stmt>> edges = cfg.getOutEdgesOf(cur);
                    if(condition.isConstant()){
                        boolean occur = false;
                        for(var edge : edges){
                            if (edge.getKind() == Edge.Kind.SWITCH_CASE){
                                if(edge.getCaseValue() == condition.getConstant()){
                                    occur = true;
                                    stmtQueue.add(edge.getTarget());
           //                         break;
                                }
                            }
                        }
                        if(!occur){
                            //处理default
                            stmtQueue.add(switchStmt.getDefaultTarget());
                        }
                    } else {
                        stmtQueue.addAll(cfg.getSuccsOf(cur));
                    }
                }
                else if (cur instanceof DefinitionStmt<?,?> definitionStmt) {
                    if(definitionStmt instanceof AssignStmt<?,?> assignStmt){
                        //无用赋值
                        LValue lv = assignStmt.getLValue();
                        RValue rv = assignStmt.getRValue();
                        if (hasNoSideEffect(assignStmt.getRValue()) && assignStmt.getLValue() instanceof Var lVar &&
                                !liveVars.getOutFact(assignStmt).contains(lVar));
                        else
                            liveCode.add(cur);
                    } else {
                        liveCode.add(cur);
                    }
                    stmtQueue.addAll(cfg.getSuccsOf(cur));
                }
                else {
                    liveCode.add(cur);
                    stmtQueue.addAll(cfg.getSuccsOf(cur));
                }
            } else {
                liveCode.add(cur);
            }
        }
        for(Stmt stmt : ir.getStmts()){
            if(!liveCode.contains(stmt)){
                deadCode.add(stmt);
            }
        }

        //留头尾
        deadCode.remove(cfg.getEntry());
        deadCode.remove(cfg.getExit());

        return deadCode;
    }

    /**
     * @return true if given RValue has no side effect, otherwise false.
     */
    private static boolean hasNoSideEffect(RValue rvalue) {
        // new expression modifies the heap
        if (rvalue instanceof NewExp ||
                // cast may trigger ClassCastException
                rvalue instanceof CastExp ||
                // static field access may trigger class initialization
                // instance field access may trigger NPE
                rvalue instanceof FieldAccess ||
                // array access may trigger NPE
                rvalue instanceof ArrayAccess) {
            return false;
        }
        if (rvalue instanceof ArithmeticExp) {
            ArithmeticExp.Op op = ((ArithmeticExp) rvalue).getOperator();
            // may trigger DivideByZeroException
            return op != ArithmeticExp.Op.DIV && op != ArithmeticExp.Op.REM;
        }
        return true;
    }
}
