package com.xetus.nodebuilder.transform;

import org.codehaus.groovy.transform.GroovyASTTransformationClass;

@GroovyASTTransformationClass("com.xetus.nodebuilder.compiler.NodeBuilderASTTransformation")
public @interface StaticNodeBuilder {}
