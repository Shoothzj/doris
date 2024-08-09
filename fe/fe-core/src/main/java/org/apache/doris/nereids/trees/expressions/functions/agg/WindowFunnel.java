// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.trees.expressions.functions.agg;

import org.apache.doris.catalog.FunctionSignature;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.functions.ExplicitlyCastableSignature;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitor;
import org.apache.doris.nereids.types.BigIntType;
import org.apache.doris.nereids.types.BooleanType;
import org.apache.doris.nereids.types.DateTimeType;
import org.apache.doris.nereids.types.DateTimeV2Type;
import org.apache.doris.nereids.types.DateType;
import org.apache.doris.nereids.types.DateV2Type;
import org.apache.doris.nereids.types.IntegerType;
import org.apache.doris.nereids.types.StringType;
import org.apache.doris.nereids.util.ExpressionUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * AggregateFunction 'window_funnel'. This class is generated by GenerateFunction.
 */
public class WindowFunnel extends NullableAggregateFunction
        implements ExplicitlyCastableSignature {

    public static final List<FunctionSignature> SIGNATURES = ImmutableList.of(
            FunctionSignature.ret(IntegerType.INSTANCE)
                    .varArgs(BigIntType.INSTANCE, StringType.INSTANCE, DateTimeType.INSTANCE, BooleanType.INSTANCE)
    );

    /**
     * constructor with 4 or more arguments.
     */
    public WindowFunnel(Expression arg0, Expression arg1, Expression arg2, Expression arg3, Expression... varArgs) {
        this(false, arg0, arg1, arg2, arg3, varArgs);
    }

    /**
     * constructor with 4 or more arguments.
     */
    public WindowFunnel(boolean distinct, Expression arg0, Expression arg1, Expression arg2,
            Expression arg3, Expression... varArgs) {
        this(distinct, false, arg0, arg1, arg2, arg3, varArgs);
    }

    public WindowFunnel(boolean distinct, boolean alwaysNullable, Expression arg0, Expression arg1, Expression arg2,
            Expression arg3, Expression... varArgs) {
        super("window_funnel", distinct, alwaysNullable,
                ExpressionUtils.mergeArguments(arg0, arg1, arg2, arg3, varArgs));
    }

    @Override
    public void checkLegalityBeforeTypeCoercion() {
        String functionName = getName();
        if (!getArgumentType(0).isIntegerLikeType()) {
            throw new AnalysisException("The window params of " + functionName + " function must be integer");
        }
        if (!getArgumentType(1).isStringLikeType()) {
            throw new AnalysisException("The mode params of " + functionName + " function must be string");
        }
        if (!getArgumentType(2).isDateLikeType()) {
            throw new AnalysisException("The 3rd param of " + functionName + " function must be DATE or DATETIME");
        }
        for (int i = 3; i < arity(); i++) {
            if (!getArgumentType(i).isBooleanType()) {
                throw new AnalysisException("The 4th and subsequent params of "
                        + functionName + " function must be boolean");
            }
        }
    }

    @Override
    public FunctionSignature computeSignature(FunctionSignature signature) {
        FunctionSignature functionSignature = super.computeSignature(signature);
        if (functionSignature.getArgType(2) instanceof DateType) {
            return functionSignature.withArgumentTypes(getArguments(), (index, originType, arg) ->
                (index == 2) ? DateTimeType.INSTANCE : originType
            );
        } else if (functionSignature.getArgType(2) instanceof DateV2Type) {
            return functionSignature.withArgumentTypes(getArguments(), (index, originType, arg) ->
                    (index == 2) ? DateTimeV2Type.SYSTEM_DEFAULT : originType
            );
        }
        return functionSignature;
    }

    /**
     * withDistinctAndChildren.
     */
    @Override
    public WindowFunnel withDistinctAndChildren(boolean distinct, List<Expression> children) {
        Preconditions.checkArgument(children.size() >= 4);
        return new WindowFunnel(distinct, alwaysNullable, children.get(0), children.get(1),
                children.get(2), children.get(3),
                children.subList(4, children.size()).toArray(new Expression[0]));
    }

    @Override
    public WindowFunnel withAlwaysNullable(boolean alwaysNullable) {
        return new WindowFunnel(distinct, alwaysNullable, children.get(0), children.get(1),
                children.get(2), children.get(3),
                children.subList(4, children.size()).toArray(new Expression[0]));
    }

    @Override
    public <R, C> R accept(ExpressionVisitor<R, C> visitor, C context) {
        return visitor.visitWindowFunnel(this, context);
    }

    @Override
    public List<FunctionSignature> getSignatures() {
        return SIGNATURES;
    }
}
