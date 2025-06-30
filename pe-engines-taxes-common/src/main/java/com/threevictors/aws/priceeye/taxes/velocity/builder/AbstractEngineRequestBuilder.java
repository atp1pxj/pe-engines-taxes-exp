package com.threevictors.aws.priceeye.taxes.velocity.builder;


import com.threevictors.aws.data.priceeye.PEItinerary;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.StringWriter;
import java.util.Properties;

public abstract class AbstractEngineRequestBuilder<T> {

    protected VelocityEngine velocityEngine;


    public abstract String buildRequest(String pointOfSale, PEItinerary itinerary, int salesDate);

    public AbstractEngineRequestBuilder() {
        initializeVelocity();
    }

    protected void initializeVelocity() {
        velocityEngine = new VelocityEngine();

        Properties velocityProperties = new Properties();
        velocityProperties.put(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        velocityProperties.put("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
        velocityProperties.put("resource.default_encoding", "UTF-8");
        velocityProperties.put("output.encoding", "UTF-8");
        velocityProperties.put("runtime.strict_mode.enable", "true");
        velocityProperties.put("runtime.strict_mode.escape", "true");
        velocityProperties.put("runtime.strict_math", "true");
        velocityProperties.put("runtime.log.log_invalid_references", "false");

        String tmpDir = System.getProperty("java.io.tmpdir");
        if (tmpDir == null) tmpDir = "/tmp/";
        else
        if (tmpDir.charAt(tmpDir.length() - 1) != '/') tmpDir = tmpDir + "/";

        velocityProperties.put("runtime.log", tmpDir + "velocity.log");

        velocityEngine.init(velocityProperties);
    }

    protected String runVelocity(T context, String templateFilename) {
        VelocityContext velocityContext = new VelocityContext();
        velocityContext.put("ctx", context);

        Template template = velocityEngine.getTemplate(templateFilename);

        StringWriter writer = new StringWriter();
        template.merge(velocityContext, writer);

        // Need the trim() call to eliminate whitespace that is in the velocity template.
        return writer.toString().trim();
    }
}
