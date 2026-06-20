package com.osrscn;

/**
 * Preset OpenAI-compatible API providers. Picking one auto-fills the API address; it stays
 * editable. {@link #CUSTOM} leaves it alone.
 */
public enum AiProvider
{
	DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
	SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1"),
	OPENAI("OpenAI", "https://api.openai.com/v1"),
	CUSTOM("自定义", "");

	private final String label;
	private final String url;

	AiProvider(String label, String url)
	{
		this.label = label;
		this.url = url;
	}

	public String getUrl()
	{
		return url;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
