package com.huawei.agent.core.parser;

import java.io.InputStream;

/** Optional extension point for applications which choose to process scanned PDFs. */
public interface OcrProvider {
    String recognize(InputStream pdf, String fileName);
}
