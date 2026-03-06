package agents_engine.core

import org.junit.jupiter.api.Test

class AllSpecsDeepIntegrationTest {

    @Test
    fun complexSystemsAreNotComplexNow() {
        open class Spec()
        class UseCase : Spec()
        class GlossaryTerm : Spec()
        class OuterReference : Spec()
        class SystemActor : Spec()
        class Feature : Spec()
        class Requirement : Spec()

        data class SpecsParcel(
            val description: String,
            val useCases: List<UseCase> = emptyList(),
            val glossaryTerms: List<GlossaryTerm> = emptyList(),
            val outerReferences: List<OuterReference> = emptyList(),
            val systemActors: List<SystemActor> = emptyList(),
            val features: List<Feature> = emptyList(),
            val requirements: List<Requirement> = emptyList(),
        )

        var specsGoodness = 0f
        val input = "Do something ppl want"

        var currentState = SpecsParcel(description = input)
        var iteration = 0

        val useCasesMaster     = agent<SpecsParcel, Spec>("use_case_master")      { execute { UseCase() } }
        val glossaryMaster     = agent<SpecsParcel, Spec>("glossary_master")      { execute { GlossaryTerm() } }
        val outerRefsMaster    = agent<SpecsParcel, Spec>("outer_refs_master")    { execute { OuterReference() } }
        val systemActorsMaster = agent<SpecsParcel, Spec>("system_actors_master") { execute { SystemActor() } }
        val featuresMaster     = agent<SpecsParcel, Spec>("features_master")      { execute { Feature() } }
        val requirementsMaster = agent<SpecsParcel, Spec>("requirements_master")  { execute { Requirement() } }

        val specsGenerationPipeline = (useCasesMaster / glossaryMaster / outerRefsMaster /
                systemActorsMaster / featuresMaster / requirementsMaster)

        val specsGatheringMaster = agent<List<Spec>, SpecsParcel>("specsGathering_master") {
            execute { specs ->
                SpecsParcel(
                    description    = currentState.description,
                    useCases       = specs.filterIsInstance<UseCase>(),
                    glossaryTerms  = specs.filterIsInstance<GlossaryTerm>(),
                    outerReferences = specs.filterIsInstance<OuterReference>(),
                    systemActors   = specs.filterIsInstance<SystemActor>(),
                    features       = specs.filterIsInstance<Feature>(),
                    requirements   = specs.filterIsInstance<Requirement>(),
                )
            }
        }

        val total = specsGenerationPipeline then specsGatheringMaster

        val specsEvaluator = agent<SpecsParcel, Float>("specsEvaluator") {
            execute { specs ->
                // Score improves with each iteration; reaches 90+ after 3 rounds
                val completeness = (specs.useCases.size + specs.glossaryTerms.size +
                        specs.outerReferences.size + specs.systemActors.size +
                        specs.features.size + specs.requirements.size).toFloat()
                minOf(completeness * 15f * (iteration + 1), 100f)
            }
        }

        while (specsGoodness < 90.0f) {
            val newSpecs = total(currentState)
            specsGoodness = specsEvaluator(newSpecs)
            currentState = newSpecs
            iteration++
        }

        assert(specsGoodness >= 90.0f)
        assert(currentState.useCases.isNotEmpty())
        assert(currentState.glossaryTerms.isNotEmpty())
        assert(currentState.systemActors.isNotEmpty())

    }
}