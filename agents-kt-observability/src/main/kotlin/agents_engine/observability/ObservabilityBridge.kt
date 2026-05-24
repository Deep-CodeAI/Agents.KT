package agents_engine.observability

import agents_engine.core.Agent
import agents_engine.core.Decision
import agents_engine.core.PipelineEvent
import agents_engine.core.observe
import agents_engine.runtime.events.AgentEvent

typealias InterceptorPoint = agents_engine.core.InterceptorPoint

interface ObservabilityBridge {
    fun onPipelineEvent(event: PipelineEvent)
    fun onAgentEvent(event: AgentEvent<*>)
    fun onInterceptorDecision(point: InterceptorPoint, decision: Decision<*>)
}

fun <IN, OUT> Agent<IN, OUT>.observe(bridge: ObservabilityBridge): Agent<IN, OUT> {
    observe { event -> bridge.onPipelineEvent(event) }
    onAgentEvent { event -> bridge.onAgentEvent(event) }
    onInterceptorDecision { point, decision -> bridge.onInterceptorDecision(point, decision) }
    return this
}
