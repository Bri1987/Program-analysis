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

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;

import java.util.List;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        // TODO - finish me
        var fact = new CPFact();
        //TODO 为什么默认全为NAC

        //后向遍历就别搞这个了吧
        if(isForward()){
            List<Var> list = cfg.getIR().getParams();
            for(Var v : list){
                //记录，要canholdInt才行
                if(canHoldInt(v))
                    fact.update(v,Value.getNAC());
            }
        }
        return fact;
    }

    @Override
    public CPFact newInitialFact() {
        // TODO - finish me
        //return null;
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        // TODO - finish me
        for(Var v : fact.keySet()){
            target.update(v,meetValue(fact.get(v),target.get(v)));
        }
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        // TODO - finish me
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        }
        if (v1.isUndef()) {
            return v2;
        }
        if (v2.isUndef()) {
            return v1;
        }
        if (v1.getConstant() == v2.getConstant()) {
            return Value.makeConstant(v1.getConstant());
        }
        return Value.getNAC();
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        // TODO - finish me

        if(stmt instanceof DefinitionStmt<?,?> stmt1){
            CPFact fact = in.copy();
            //kill b
            var lv = stmt1.getLValue();
            if(lv instanceof Var v &&  canHoldInt(v)){
                fact.remove(v);

                //In[S] -{(x,_)}赋给out
                //out.copyFrom(fact);

                //并上gen
                Value res = evaluate(stmt1.getRValue(),in);
                //这里没有必要meetInto, 因为是已经被杀掉的左值
                fact.update(v, res);

                return out.copyFrom(fact);
            }
        }

        //记录, 非assign_stmt也要处理
        return out.copyFrom(in);
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {

        if(exp instanceof IntLiteral c){
            return Value.makeConstant(c.getValue());
        }

        if(exp instanceof Var v){
            //为什么是这个
            //直接在目前的in中找有没有v的记录，不直接返回v的原因是可能还undef(in中没出现记录)
            return in.get(v);
        }

        if(exp instanceof BinaryExp binaryExp){
            //剩下都是二元操作符,拿到最新值
            Value left = in.get(binaryExp.getOperand1());
            Value right = in.get(binaryExp.getOperand2());

            if(left.isConstant() && right.isConstant()){
                if(binaryExp instanceof ArithmeticExp arithmeticExp){
                    if(arithmeticExp.getOperator() == ArithmeticExp.Op.ADD){
                        return Value.makeConstant(left.getConstant() + right.getConstant());
                    } else if(arithmeticExp.getOperator() == ArithmeticExp.Op.SUB){
                        return Value.makeConstant(left.getConstant() - right.getConstant());
                    } else if(arithmeticExp.getOperator() == ArithmeticExp.Op.MUL){
                        return Value.makeConstant(left.getConstant() * right.getConstant());
                    }else if(arithmeticExp.getOperator() == ArithmeticExp.Op.DIV){
                        if(right.getConstant() == 0){
                            return Value.getUndef();
                        } else
                            return Value.makeConstant(left.getConstant() / right.getConstant());
                    } else if(arithmeticExp.getOperator() == ArithmeticExp.Op.REM){
                        if(right.getConstant() == 0){
                            return Value.getUndef();
                        } else
                            return Value.makeConstant(left.getConstant() % right.getConstant());
                    }
                } else if(binaryExp instanceof ConditionExp conditionExp){
                    if (conditionExp.getOperator() == ConditionExp.Op.EQ) {
                        return Value.makeConstant((left.getConstant() == right.getConstant()) ? 1 : 0);
                    } else if (conditionExp.getOperator() == ConditionExp.Op.GE) {
                        return Value.makeConstant((left.getConstant() >= right.getConstant()) ? 1 : 0);
                    } else if (conditionExp.getOperator() == ConditionExp.Op.GT) {
                        return Value.makeConstant((left.getConstant() > right.getConstant()) ? 1 : 0);
                    } else if (conditionExp.getOperator() == ConditionExp.Op.LE) {
                        return Value.makeConstant((left.getConstant() <= right.getConstant()) ? 1 : 0);
                    } else if (conditionExp.getOperator() == ConditionExp.Op.LT) {
                        return Value.makeConstant((left.getConstant() < right.getConstant()) ? 1 : 0);
                    } else if (conditionExp.getOperator() == ConditionExp.Op.NE) {
                        return Value.makeConstant((left.getConstant() != right.getConstant()) ? 1 : 0);
                    }
                } else if(binaryExp instanceof ShiftExp shiftExp){
                    if (shiftExp.getOperator() == ShiftExp.Op.SHL) {
                        return Value.makeConstant(left.getConstant() << right.getConstant());
                    } else if (shiftExp.getOperator() == ShiftExp.Op.SHR) {
                        return Value.makeConstant(left.getConstant() >> right.getConstant());
                    } else if (shiftExp.getOperator() == ShiftExp.Op.USHR) {
                        return Value.makeConstant(left.getConstant() >>> right.getConstant());
                    }
                } else if(binaryExp instanceof BitwiseExp bitwiseExp){
                    if (bitwiseExp.getOperator() == BitwiseExp.Op.AND) {
                        return Value.makeConstant(left.getConstant() & right.getConstant());
                    } else if (bitwiseExp.getOperator() == BitwiseExp.Op.OR) {
                        return Value.makeConstant(left.getConstant() | right.getConstant());
                    } else if (bitwiseExp.getOperator() == BitwiseExp.Op.XOR) {
                        return Value.makeConstant(left.getConstant() ^ right.getConstant());
                    }
                }  else {
                    return Value.getUndef();
                }
            } else if(left.isNAC() || right.isNAC()){
                // !!! 注意还是有除数为0的情况
                if(right.isConstant() && right.getConstant() == 0 && (binaryExp.getOperator() == ArithmeticExp.Op.DIV || binaryExp.getOperator() == ArithmeticExp.Op.REM)){
                    return Value.getUndef();
                }
                return Value.getNAC();
            }
            //有undef的话
            else {
                return Value.getUndef();
            }
        }

        //记录，不是binary，比如过程间类型
        return Value.getNAC();
    }
}
