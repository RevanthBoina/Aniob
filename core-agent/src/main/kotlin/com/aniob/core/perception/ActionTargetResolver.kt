package com.aniob.core.perception

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.grounding.AniobGroundingResolver

/**
 * The ONE action-level target-resolution contract, shared by the ordinary executor path and the
 * action-burst path.
 *
 * `SemanticTarget.SomIndex(ActionBurst.UNNAMED_TARGET)` is the intentional placeholder an `input`
 * carries when the model typed without naming a field. It is not an element id; it means "the one
 * obvious editable field on this screen". Every resolver that grounded an action by walking the
 * screen therefore needs the same two rules the burst runner already had:
 *
 *  1. the sentinel resolves only for [AniobAction.InputText];
 *  2. it resolves only when the screen has exactly one editable, visible, enabled, non-password
 *     field — anything else fails closed (the caller asks, the model never guesses).
 *
 * [resolve] is the entry point; it also enforces the dual rule for named targets (an input may
 * only land in an editable node), which the burst planner applied as [BurstPlanScope.canReceive]
 * but the ordinary executor did not.
 */
object ActionTargetResolver {

    /**
     * The single editable field on [screen], or null when there is not exactly one usable field.
     * Password fields never count as the obvious field: an unnamed input may not type into one.
     */
    fun soleEditableField(screen: AniobScreenState): AniobNode? =
        screen.nodes.filter { it.isEditable && it.isVisibleToUser && it.isEnabled && !it.isPassword }.singleOrNull()

    /** True when [action]'s target is the unnamed-input sentinel. */
    fun isUnnamedInput(action: AniobAction): Boolean =
        action is AniobAction.InputText &&
            (action.semanticTarget() as? SemanticTarget.SomIndex)?.index == ActionBurst.UNNAMED_TARGET

    /**
     * Grounded target for [action] on [screen], or null when the target cannot be resolved without
     * inventing a node. A null result must surface to the caller as a miss — never as a coordinate.
     */
    fun resolve(action: AniobAction, screen: AniobScreenState): AniobGroundingResolver.Resolved? {
        if (isUnnamedInput(action)) {
            val field = soleEditableField(screen) ?: return null
            if (field.isPassword) return null
            return AniobGroundingResolver.Resolved(
                node = field,
                x = field.centerX,
                y = field.centerY,
                source = field.source
            )
        }
        val target = action.semanticTarget() ?: return null
        // The sentinel means nothing to a tap or a long press.
        if (target is SemanticTarget.SomIndex && target.index == ActionBurst.UNNAMED_TARGET) return null
        val resolved = AniobGroundingResolver.resolve(target, screen) ?: return null
        // An input may only land in a field; a named non-editable node is a miss, not a type-into-button.
        if (action is AniobAction.InputText && !resolved.node.isEditable) return null
        return resolved
    }
}
