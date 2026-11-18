package com.comfyremote.app.feature.ai

import androidx.annotation.StringRes
import com.comfyremote.app.R
import com.comfyremote.app.SkillExecution
import com.comfyremote.app.SkillInputSource
import com.comfyremote.app.SkillOutput
import com.comfyremote.app.SkillScope

/** Exhaustive, localized explanations for *every* configurable skill tag. */
internal object SkillHelpCatalog {
    @StringRes fun scopeDescription(value: SkillScope): Int = when (value) {
        SkillScope.TXT2IMG -> R.string.ai_help_scope_txt2img
        SkillScope.IMG2IMG -> R.string.ai_help_scope_img2img
        SkillScope.TXT2VIDEO -> R.string.ai_help_scope_txt2video
        SkillScope.IMG2VIDEO -> R.string.ai_help_scope_img2video
        SkillScope.START_END_VIDEO -> R.string.ai_help_scope_start_end_video
        SkillScope.MULTI_REFERENCE -> R.string.ai_help_scope_multi_reference
        SkillScope.AUDIO -> R.string.ai_help_scope_audio
    }

    @StringRes fun inputDescription(value: SkillInputSource): Int = when (value) {
        SkillInputSource.CURRENT_TEXT -> R.string.ai_help_input_current_text
        SkillInputSource.POSITIVE_PROMPT -> R.string.ai_help_input_positive_prompt
        SkillInputSource.NEGATIVE_PROMPT -> R.string.ai_help_input_negative_prompt
        SkillInputSource.CURRENT_IMAGE -> R.string.ai_help_input_current_image
        SkillInputSource.ALL_REFERENCES -> R.string.ai_help_input_all_references
        SkillInputSource.START_END_FRAMES -> R.string.ai_help_input_start_end_frames
        SkillInputSource.PROJECT_CONTEXT -> R.string.ai_help_input_project_context
    }

    @StringRes fun executionDescription(value: SkillExecution): Int = when (value) {
        SkillExecution.TEXT_MODEL -> R.string.ai_help_execution_text_model
        SkillExecution.VISION_MODEL -> R.string.ai_help_execution_vision_model
        SkillExecution.MULTI_STAGE -> R.string.ai_help_execution_multi_stage
    }

    @StringRes fun outputDescription(value: SkillOutput): Int = when (value) {
        SkillOutput.REPLACE_FIELD -> R.string.ai_help_output_replace_field
        SkillOutput.APPEND -> R.string.ai_help_output_append
        SkillOutput.CANDIDATES -> R.string.ai_help_output_candidates
        SkillOutput.MULTI_FIELD -> R.string.ai_help_output_multi_field
    }

}
