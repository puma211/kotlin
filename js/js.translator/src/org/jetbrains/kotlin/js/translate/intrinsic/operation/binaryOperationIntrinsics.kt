/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.intrinsic.operation

import org.jetbrains.kotlin.js.backend.ast.JsExpression
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.utils.BindingUtils.getCallableDescriptorForOperationExpression
import org.jetbrains.kotlin.js.translate.utils.PsiUtils.getOperationToken
import org.jetbrains.kotlin.js.translate.utils.getPrecisePrimitiveType
import org.jetbrains.kotlin.js.translate.utils.getPrimitiveNumericComparisonInfo
import org.jetbrains.kotlin.lexer.KtToken
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.types.KotlinType

interface BinaryOperationIntrinsic {
    fun apply(expression: KtBinaryExpression, left: JsExpression, right: JsExpression, context: TranslationContext): JsExpression
}

class BinaryOperationIntrinsics {

    private data class IntrinsicKey(
        val token: KtToken,
        val function: FunctionDescriptor,
        val leftType: KotlinType?,
        val rightType: KotlinType?
    )

    private val intrinsicCache = mutableMapOf<IntrinsicKey, BinaryOperationIntrinsic?>()

    fun getIntrinsic(expression: KtBinaryExpression, context: TranslationContext): BinaryOperationIntrinsic? {
        val descriptor = getCallableDescriptorForOperationExpression(context.bindingContext(), expression) as? FunctionDescriptor ?: return null

        val (leftType, rightType) = binaryOperationTypes(expression, context)

        val token = getOperationToken(expression)

        return computeAndCache(IntrinsicKey(token, descriptor, leftType, rightType))
    }

    private val factories = listOf(LongCompareToBOIF, EqualsBOIF, CompareToBOIF, AssignmentBOIF)

    private fun computeAndCache(key: IntrinsicKey): BinaryOperationIntrinsic? {
        val result = intrinsicCache.getOrPut(key) {
            factories.find { factory ->
                factory.getSupportTokens().contains(key.token) && factory.getIntrinsic(key.function, key.leftType, key.rightType) != null
            }
            NO_INTRINSIC
        }

        return if (result !== NO_INTRINSIC) result else null
    }

    // Special object to cache the "no intrinsic" outcome
    private object NO_INTRINSIC : BinaryOperationIntrinsic {
        override fun apply(expression: KtBinaryExpression, left: JsExpression, right: JsExpression, context: TranslationContext): JsExpression =
            throw UnsupportedOperationException("BinaryOperationIntrinsic#NO_INTRINSIC_#apply")
    }
}

// Takes into account smart-casts (needed for IEEE 754 comparisons)
fun binaryOperationTypes(expression: KtBinaryExpression, context: TranslationContext): Pair<KotlinType?, KotlinType?> {
    val info = context.getPrimitiveNumericComparisonInfo(expression)
    if (info != null) {
        return info.leftType to info.rightType
    }
    return expression.left?.let { context.getPrecisePrimitiveType(it) } to expression.right?.let { context.getPrecisePrimitiveType(it) }
}

interface BinaryOperationIntrinsicFactory {

    fun getSupportTokens(): Set<KtToken>

    fun getIntrinsic(descriptor: FunctionDescriptor, leftType: KotlinType?, rightType: KotlinType?): BinaryOperationIntrinsic?
}