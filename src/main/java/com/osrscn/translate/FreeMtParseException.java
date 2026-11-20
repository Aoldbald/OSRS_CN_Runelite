package com.osrscn.translate;

/**
 * A Bing translation response that cannot be used as this line's translation.
 *
 * <p>{@link FreeMtClient#parse} funnels every {@link RuntimeException} (Gson syntax errors, array
 * index errors, type errors) into this checked type, so the caller only has to catch this one
 * exception and treat the line as a content failure (P3). If those runtime exceptions escaped
 * instead, they would fall through to the generic request failure path and the line would be retried
 * forever.
 */
final class FreeMtParseException extends Exception
{
	private static final long serialVersionUID = 1L;

	FreeMtParseException(String message)
	{
		super(message);
	}

	FreeMtParseException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
