package com.inkyandroid.ui.extras.snippets

import androidx.annotation.StringRes
import com.inkyandroid.R

/**
 * The string resources of the built-in snippet tree, by the stable ids of `:core`'s `InkSnippets`:
 * categories and snippets have separate tables because two snippets share a name ("Condition") and a
 * category may one day share an id with a snippet.
 */
internal object SnippetNames {
    @StringRes
    val categories: Map<String, Int> = mapOf(
        "basic_structure" to R.string.snippet_category_basic_structure,
        "choices" to R.string.snippet_category_choices,
        "variables" to R.string.snippet_category_variables,
        "inline_logic" to R.string.snippet_category_inline_logic,
        "multiline_logic" to R.string.snippet_category_multiline_logic,
        "comments" to R.string.snippet_category_comments,
        "list_handling" to R.string.snippet_category_list_handling,
        "useful_functions" to R.string.snippet_category_useful_functions,
        "useful_systems" to R.string.snippet_category_useful_systems,
        "full_stories" to R.string.snippet_category_full_stories,
    )

    @StringRes
    val snippets: Map<String, Int> = mapOf(
        "knot" to R.string.snippet_knot,
        "stitch" to R.string.snippet_stitch,
        "divert" to R.string.snippet_divert,
        "ending" to R.string.snippet_ending,
        "choice_basic" to R.string.snippet_choice_basic,
        "choice_sticky" to R.string.snippet_choice_sticky,
        "choice_suppressed" to R.string.snippet_choice_suppressed,
        "choice_mixed" to R.string.snippet_choice_mixed,
        "variable_global" to R.string.snippet_variable_global,
        "variable_temporary" to R.string.snippet_variable_temporary,
        "variable_modify" to R.string.snippet_variable_modify,
        "variable_type" to R.string.snippet_variable_type,
        "inline_condition" to R.string.snippet_inline_condition,
        "multiline_condition" to R.string.snippet_multiline_condition,
        "comment_line" to R.string.snippet_comment_line,
        "comment_block" to R.string.snippet_comment_block,
        "list_pop" to R.string.snippet_list_pop,
        "list_pop_random" to R.string.snippet_list_pop_random,
        "list_next_prev" to R.string.snippet_list_next_prev,
        "list_item_is_member_of" to R.string.snippet_list_item_is_member_of,
        "list_random_subset" to R.string.snippet_list_random_subset,
        "list_random_subset_of_size" to R.string.snippet_list_random_subset_of_size,
        "list_string_to_list" to R.string.snippet_list_string_to_list,
        "logic_maybe" to R.string.snippet_logic_maybe,
        "math_divisor" to R.string.snippet_math_divisor,
        "math_abs" to R.string.snippet_math_abs,
        "flow_came_from" to R.string.snippet_flow_came_from,
        "flow_seen_very_recently" to R.string.snippet_flow_seen_very_recently,
        "flow_seen_more_recently_than" to R.string.snippet_flow_seen_more_recently_than,
        "flow_seen_this_scene" to R.string.snippet_flow_seen_this_scene,
        "flow_thread_in_tunnel" to R.string.snippet_flow_thread_in_tunnel,
        "print_a_or_an" to R.string.snippet_print_a_or_an,
        "print_uppercase" to R.string.snippet_print_uppercase,
        "print_number" to R.string.snippet_print_number,
        "print_list_with_commas" to R.string.snippet_print_list_with_commas,
        "system_list_items_as_integers" to R.string.snippet_system_list_items_as_integers,
        "system_swing_variables" to R.string.snippet_system_swing_variables,
        "system_storylets" to R.string.snippet_system_storylets,
        "story_crime_scene" to R.string.snippet_story_crime_scene,
        "story_swindlestones" to R.string.snippet_story_swindlestones,
        "story_pontoon" to R.string.snippet_story_pontoon,
        "story_intercept" to R.string.snippet_story_intercept,
    )
}
