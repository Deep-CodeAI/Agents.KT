package agents_engine.web

import agents_engine.generation.Generable
import agents_engine.generation.Guide

@Generable("Arguments for fetching a single web URL")
data class WebFetchArgs(
    @Guide("Absolute http or https URL to fetch")
    val url: String,
)
