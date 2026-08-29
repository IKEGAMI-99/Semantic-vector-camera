package com.ikegami.svcam.semantic

object SemanticSchema {
    const val ID = "SVCAM-896-V1"
    const val GLOBAL_DIMENSIONS = 256
    const val OBJECT_SLOTS = 16
    const val OBJECT_DIMENSIONS = 32
    const val RELATION_DIMENSIONS = 128
    const val TOTAL_DIMENSIONS = 896

    val SCENE_LABELS: List<String> = listOf(
        "indoor", "outdoor", "urban", "rural",
        "natural", "residential", "commercial", "industrial",
        "transportation", "water_scene", "mountain", "forest",
        "coast", "road", "street", "building_dense",
        "building_sparse", "open_space", "enclosed_space", "public_space",
        "private_space", "historic", "modern", "futuristic",
        "temporary_space", "abandoned", "constructed", "organic",
        "large_scale", "small_scale", "near_field", "far_field"
    )

    val LIGHTING_LABELS: List<String> = listOf(
        "brightness", "darkness", "high_contrast", "low_contrast",
        "natural_light", "artificial_light", "direct_sunlight", "diffuse_light",
        "backlight", "front_light", "side_light", "rim_light",
        "hard_light", "soft_light", "shadow_strength", "shadow_softness",
        "warm_light", "cool_light", "neon_light", "street_light",
        "window_light", "screen_light", "fire_light", "overcast_light",
        "golden_hour", "blue_hour", "night_illumination", "silhouette",
        "specular_highlight", "deep_shadow", "even_exposure", "dramatic_lighting"
    )

    val COLOR_LABELS: List<String> = listOf(
        "red", "orange", "yellow", "green",
        "cyan", "blue", "purple", "magenta",
        "pink", "brown", "beige", "white",
        "gray", "black", "warm_palette", "cool_palette",
        "neutral_palette", "high_saturation", "low_saturation", "monochrome",
        "colorful", "pastel", "vivid", "muted",
        "dark_palette", "bright_palette", "color_contrast", "color_harmony",
        "skin_tone_presence", "vegetation_green", "sky_blue", "artificial_color_cast"
    )

    val ATMOSPHERE_LABELS: List<String> = listOf(
        "calm", "busy", "lonely", "cheerful",
        "melancholic", "mysterious", "threatening", "peaceful",
        "nostalgic", "futuristic_mood", "mundane", "dramatic",
        "romantic", "clinical", "cozy", "cold_mood",
        "warm_mood", "energetic", "quiet", "chaotic",
        "orderly", "sacred", "playful", "serious",
        "dreamlike", "surreal", "documentary", "cinematic",
        "intimate", "grand", "safe_feeling", "uneasy"
    )

    val COMPOSITION_LABELS: List<String> = listOf(
        "symmetry", "asymmetry", "centered_subject", "off_center_subject",
        "depth", "flatness", "strong_perspective", "weak_perspective",
        "foreground_dominance", "midground_dominance", "background_dominance", "high_horizon",
        "low_horizon", "center_horizon", "single_vanishing_point", "multiple_vanishing_points",
        "negative_space", "visual_complexity", "visual_simplicity", "leading_lines",
        "frame_within_frame", "repetition", "pattern", "balanced_mass",
        "unbalanced_mass", "vertical_emphasis", "horizontal_emphasis", "diagonal_emphasis",
        "close_up", "medium_view", "wide_view", "panoramic_feel"
    )

    val ENVIRONMENT_LABELS: List<String> = listOf(
        "rain", "snow", "fog", "mist",
        "cloudy", "clear_sky", "storm", "wet_ground",
        "dry_ground", "ice", "dust", "smoke",
        "vegetation", "flowers", "bare_trees", "water_surface",
        "waves", "standing_water", "wind_evidence", "summer_cues",
        "winter_cues", "spring_cues", "autumn_cues", "daytime",
        "nighttime", "dawn", "dusk", "good_visibility",
        "poor_visibility", "clean_environment", "dirty_environment", "weather_uncertainty"
    )

    val HUMAN_CONTEXT_LABELS: List<String> = listOf(
        "human_presence", "single_person", "multiple_people", "crowd",
        "child_presence", "adult_presence", "elderly_presence", "face_visible",
        "face_hidden", "looking_at_camera", "looking_away", "walking",
        "running", "sitting", "standing", "working",
        "eating", "talking", "using_device", "driving",
        "shopping", "playing", "posing", "candid_activity",
        "social_interaction", "isolation", "formal_clothing", "casual_clothing",
        "human_motion", "human_stillness", "personal_space", "public_activity"
    )

    val UNCERTAINTY_LABELS: List<String> = listOf(
        "overall_confidence", "overall_ambiguity", "scene_familiarity", "scene_novelty",
        "semantic_complexity", "visual_clutter", "occlusion_level", "blur_level",
        "motion_blur", "low_light_uncertainty", "weather_uncertainty_global", "object_identity_uncertainty",
        "spatial_uncertainty", "scale_uncertainty", "depth_uncertainty", "time_of_day_uncertainty",
        "indoor_outdoor_uncertainty", "human_identity_uncertainty", "text_unreadable", "partial_objects",
        "reflection_confusion", "shadow_confusion", "transparent_surface_confusion", "screen_content_confusion",
        "artwork_reality_confusion", "unusual_object", "contradictory_cues", "hallucination_risk",
        "missing_context", "cropped_context", "decoder_freedom", "semantic_entropy"
    )

    val GLOBAL_LABELS: List<String> = listOf(
        SCENE_LABELS, LIGHTING_LABELS, COLOR_LABELS, ATMOSPHERE_LABELS,
        COMPOSITION_LABELS, ENVIRONMENT_LABELS, HUMAN_CONTEXT_LABELS, UNCERTAINTY_LABELS,
    ).flatten()

    val OBJECT_LABELS: List<String> = listOf(
        "human", "animal", "vehicle", "building",
        "vegetation", "furniture", "device", "text_sign",
        "center_x", "center_y", "width", "height",
        "brightness", "saturation", "warm", "cool",
        "textured", "reflective", "transparent", "sharp",
        "importance", "confidence", "motion", "artificial",
        "natural", "foreground", "background", "emotional_relevance",
        "identity_ambiguity", "occlusion", "partial_visibility", "unusualness"
    )

    val RELATION_SPATIAL_LABELS: List<String> = listOf(
        "left_of", "right_of", "above", "below",
        "in_front_of", "behind", "near", "far",
        "inside", "contains", "overlapping", "touching",
        "aligned_horizontal", "aligned_vertical", "clustered", "separated",
        "surrounding", "surrounded_by", "at_edge", "at_center",
        "on_surface", "under_surface", "connected", "disconnected",
        "same_depth", "different_depth", "toward_horizon", "across_frame",
        "parallel", "perpendicular", "repeating_spatially", "spatial_ambiguity"
    )

    val RELATION_INTERACTION_LABELS: List<String> = listOf(
        "person_near_object", "person_holding_object", "person_using_object", "person_riding_vehicle",
        "person_inside_vehicle", "person_facing_object", "person_facing_person", "people_interacting",
        "people_grouped", "people_separated", "animal_near_person", "animal_near_object",
        "vehicle_on_road", "vehicle_near_vehicle", "building_near_road", "vegetation_near_building",
        "object_on_furniture", "device_in_hand", "text_on_object", "light_from_object",
        "reflection_of_object", "shadow_from_object", "object_supports_object", "object_attached_to_object",
        "object_contains_object", "object_partially_hidden", "object_framed_by_object", "object_points_to_object",
        "gaze_relation", "motion_relation", "functional_relation", "interaction_uncertainty"
    )

    val RELATION_COMPOSITION_LABELS: List<String> = listOf(
        "foreground_background_link", "foreground_subject_anchor", "background_context_strength", "central_subject_relation",
        "edge_subject_relation", "leading_line_to_subject", "vanishing_point_relation", "symmetry_relation",
        "balance_relation", "scale_comparison", "size_contrast", "color_similarity",
        "color_contrast_relation", "brightness_similarity", "brightness_contrast_relation", "texture_similarity",
        "texture_contrast_relation", "shape_similarity", "shape_contrast_relation", "repetition_relation",
        "pattern_relation", "visual_hierarchy", "primary_secondary_relation", "negative_space_relation",
        "framing_relation", "layering_relation", "depth_stack", "occlusion_chain",
        "grouping_relation", "isolation_relation", "scene_coherence", "composition_uncertainty"
    )

    val RELATION_DYNAMICS_LABELS: List<String> = listOf(
        "moving_together", "moving_apart", "approaching", "receding",
        "crossing_paths", "stationary_relation", "motion_direction_left", "motion_direction_right",
        "motion_direction_up", "motion_direction_down", "camera_motion_hint", "wind_affects_objects",
        "rain_affects_surface", "water_motion", "traffic_flow", "crowd_flow",
        "action_reaction", "cause_effect_hint", "before_after_hint", "temporary_event",
        "stable_structure", "unstable_structure", "light_change_hint", "weather_change_hint",
        "human_activity_focus", "machine_activity", "natural_activity", "potential_collision",
        "potential_contact", "time_progression", "dynamic_tension", "dynamics_uncertainty"
    )

    val RELATION_LABELS: List<String> = listOf(
        RELATION_SPATIAL_LABELS, RELATION_INTERACTION_LABELS,
        RELATION_COMPOSITION_LABELS, RELATION_DYNAMICS_LABELS,
    ).flatten()

    init {
        check(GLOBAL_LABELS.size == GLOBAL_DIMENSIONS)
        check(OBJECT_LABELS.size == OBJECT_DIMENSIONS)
        check(RELATION_LABELS.size == RELATION_DIMENSIONS)
        check(GLOBAL_DIMENSIONS + OBJECT_SLOTS * OBJECT_DIMENSIONS + RELATION_DIMENSIONS == TOTAL_DIMENSIONS)
    }

    fun promptCatalog(): String = buildString {
        appendLine("GLOBAL labels (missing means 0):")
        appendLine(GLOBAL_LABELS.joinToString(","))
        appendLine("OBJECT score labels (bbox is supplied separately as [center_x,center_y,width,height]):")
        appendLine(OBJECT_LABELS.filterNot { it in setOf("center_x", "center_y", "width", "height") }.joinToString(","))
        appendLine("RELATION labels (missing means 0):")
        appendLine(RELATION_LABELS.joinToString(","))
    }
}
