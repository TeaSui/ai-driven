package com.workflow.engine

import com.workflow.api.ConditionDefinition
import com.workflow.api.ConditionOperator
import com.workflow.api.ExecutionContext

/**
 * Evaluates step conditions against the current execution context.
 */
interface ConditionEvaluator {
    fun evaluate(conditions: List<ConditionDefinition>, context: ExecutionContext): Boolean
}

/**
 * Default condition evaluator supporting common operators.
 */
class DefaultConditionEvaluator : ConditionEvaluator {

    override fun evaluate(conditions: List<ConditionDefinition>, context: ExecutionContext): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.all { evaluateCondition(it, context) }
    }

    private fun evaluateCondition(condition: ConditionDefinition, context: ExecutionContext): Boolean {
        val fieldValue = context.get(condition.field)

        return when (condition.operator) {
            ConditionOperator.EQUALS -> fieldValue?.toString() == condition.value.toString()
            ConditionOperator.NOT_EQUALS -> fieldValue?.toString() != condition.value.toString()
            ConditionOperator.CONTAINS -> fieldValue?.toString()?.contains(condition.value.toString()) ?: false
            ConditionOperator.GREATER_THAN -> compareNumbers(fieldValue, condition.value) > 0
            ConditionOperator.LESS_THAN -> compareNumbers(fieldValue, condition.value) < 0
            ConditionOperator.IS_NULL -> fieldValue == null
            ConditionOperator.IS_NOT_NULL -> fieldValue != null
        }
    }

    private fun compareNumbers(a: Any?, b: Any): Int {
        val aDouble = a?.toString()?.toDoubleOrNull() ?: return -1
        val bDouble = b.toString().toDoubleOrNull() ?: return -1
        return aDouble.compareTo(bDouble)
    }
}
