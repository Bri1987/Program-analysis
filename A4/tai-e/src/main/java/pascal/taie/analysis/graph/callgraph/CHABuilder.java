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

package pascal.taie.analysis.graph.callgraph;

import pascal.taie.World;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Implementation of the CHA algorithm.
 */
class CHABuilder implements CGBuilder<Invoke, JMethod> {

    private ClassHierarchy hierarchy;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        hierarchy = World.get().getClassHierarchy();
        return buildCallGraph(World.get().getMainMethod());
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        DefaultCallGraph callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);
        // TODO - finish me
        Queue<JMethod> methodQueue = new ArrayDeque<>();
        methodQueue.add(entry);
        while (!methodQueue.isEmpty()){
            JMethod method = methodQueue.poll();

            // 记录，已经reach处理过的就不再处理了，否则会形成环，死循环
            if(callGraph.reachableMethods().noneMatch(m -> m.equals(method))){
//                if(!callGraph.reachableMethods.contains(method)){
                    callGraph.addReachableMethod(method);
//                }

                //找出这method里的所有引用的方法调用点
                callGraph.callSitesIn(method).forEach(cs -> {
                    Set<JMethod> set = resolve(cs);
                    methodQueue.addAll(set);
                    for(JMethod m : set){
                        callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(cs),cs,m));
                    }
                });
            }

        }
        return callGraph;
    }

    /**
     * Resolves call targets (callees) of a call site via CHA.
     */
    private Set<JMethod> resolve(Invoke callSite) {
        // TODO - finish me
        Set<JMethod> T = new HashSet<>();
        MethodRef methodRef = callSite.getMethodRef();
        JClass jClass = methodRef.getDeclaringClass();
        Subsignature subsignature = methodRef.getSubsignature();
        JMethod method = jClass.getDeclaredMethod(subsignature);

        if(callSite.isStatic()){
            T.add(method);
        } else if(callSite.isSpecial()) {
            JMethod special_method =  dispatch(jClass,subsignature);
            if(special_method != null)
                T.add(special_method);
        } else if(callSite.isVirtual() || callSite.isInterface()){
            //TODO declared type of receriver variable at cs和jclass是一个class吗
            Queue<JClass> classQueue = new ArrayDeque<>();
            classQueue.add(jClass);
            while (!classQueue.isEmpty()) {
                JClass jClass1 = classQueue.poll();
                JMethod jMethod = dispatch(jClass1,subsignature);
                if(jMethod != null)
                    T.add(jMethod);
                classQueue.addAll(hierarchy.getDirectSubclassesOf(jClass1));
                // 记录，主要是接口需要加这两个
                classQueue.addAll(hierarchy.getDirectSubinterfacesOf(jClass1));
                classQueue.addAll(hierarchy.getDirectImplementorsOf(jClass1));
            }
        }
        return T;
    }

    /**
     * Looks up the target method based on given class and method subsignature.
     *
     * @return the dispatched target method, or null if no satisfying method
     * can be found.
     */
    private JMethod dispatch(JClass jclass, Subsignature subsignature) {
        // TODO - finish me
        JMethod method = jclass.getDeclaredMethod(subsignature);
        if(method != null && !method.isAbstract()){
            return method;
        } else {
            JClass super_jClass = jclass.getSuperClass();
            if(super_jClass != null)
                return dispatch(super_jClass,subsignature);
            return null;
        }
    }
}
