package com.osrscn;

/** Where AI fallback translations come from. */
public enum AiBackend
{
	/** Local Ollama server ({@code /api/chat}). Free, private, needs a GPU. */
	OLLAMA("本地 Ollama"),
	/** OpenAI-compatible online API ({@code /chat/completions}, Bearer key). DeepSeek/OpenAI/... */
	OPENAI("在线 API");

	private final String label;

	AiBackend(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
