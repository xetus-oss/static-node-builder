package com.xetus.nodebuilder.compiler;

import java.util.ArrayList;
import java.util.List;

import static org.codehaus.groovy.ast.tools.GeneralUtils.ctorX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.returnS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.declS;
import static org.codehaus.groovy.ast.tools.GeneralUtils.callX;
import static org.codehaus.groovy.ast.tools.GeneralUtils.args;
import static org.codehaus.groovy.ast.tools.GeneralUtils.stmt;

import groovy.lang.DelegatesTo;
import groovy.transform.CompileStatic;
import groovy.transform.InheritConstructors;
import groovy.util.Node;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.DelegateASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.codehaus.groovy.transform.InheritConstructorsASTTransformation;

import com.xetus.nodebuilder.runtime.ConstrainedNodeBuilder;

/**
 * An AST transformation to allow for statically compiled {@link Node} built 
 * according to a schema defined in an {@link ConstrainedNodeBuilder} sub-class. 
 * An example usage would be:
 * 
 * <pre>
 * class MyBuilder extends ConstrainedNodeBuilder {
 *  static schema = {
 *    html {
 *      body {
 *        a()
 *        p()
 *      }
 *    }
 *  }
 * }
 * </pre>
 * Which would allow consumers to build node trees using the following format:
 * 
 * <pre>
 * MyBuilder builder = new MyBuilder()
 * builder.html {
 *  body {
 *    p("This is some text in the paragraph")
 *    a([href: "http://www.link.com", data: "these are attributes"], "LINK")
 *  }
 * }
 * </pre>
 * 
 * TODO: determine whether this should support restricting attributes in the
 * schema
 * 
 * Note that much of this is respectfully copied from Cedric Champeau's 
 * staticbuilder project of a few years ago, including the idea
 * 
 * @author tmeneau
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class NodeBuilderASTTransformation extends AbstractASTTransformation {

  private static final ClassNode NODE_BUILDER_CLASS_NODE = 
      ClassHelper.make(ConstrainedNodeBuilder.class);
  private static final ClassNode NODE_CLASS_NODE = ClassHelper.make(Node.class);

  private static final String SCHEMA_FIELD_NAME = "schema";

  private SourceUnit sourceUnit;
  private ClassNode builderNode;

  @Override
  public void visit(ASTNode[] nodes, SourceUnit source) {
    init(nodes, source);
    this.sourceUnit = source;
    AnnotatedNode annotated = (AnnotatedNode) nodes[1];

    if (!(annotated instanceof ClassNode)) {
      addError("Expected class node", annotated);
    }
    builderNode = (ClassNode) annotated;
    ClassNode superClass = builderNode.getSuperClass();

    // Ensure the visiting class extends the ConstrainedNodeBuilder class
    if (superClass.equals(ClassHelper.OBJECT_TYPE.getPlainNodeReference())) {
      builderNode.setSuperClass(NODE_BUILDER_CLASS_NODE);
    }

    createNodeClasses(builderNode, source);
  }

  private void createNodeClasses(ClassNode classNode, SourceUnit source) {
    FieldNode schemaNode = classNode.getField(SCHEMA_FIELD_NAME);
    if (schemaNode == null) {
      return;
    }

    Expression initialExpression = schemaNode.getInitialExpression();
    if (initialExpression == null
        || (!(initialExpression instanceof ClosureExpression))) {
      addError("Schema definition must be a closure of Node definitions",
               schemaNode);
      return;
    }

    buildNodeClassesFromSchema(classNode, (ClosureExpression) initialExpression);

    // The schema closure needs to be removed to avoid attempts to evaluate
    // it at runtime
    schemaNode.setInitialValueExpression(null);
    classNode.removeField("schema");
  }

  private List<ClassNode> buildNodeClassesFromSchema(ClassNode parent,
                                                     ClosureExpression cle) {
    Statement code = cle.getCode();
    return buildNodeClassesFromStatement(parent, code);
  }

  private List<ClassNode> buildNodeClassesFromStatement(ClassNode parent,
                                                        Statement code) {
    List<ClassNode> childNodes = new ArrayList<ClassNode>();

    /*
     * PARENT { FIRST_CHILD { ... } SECOND_CHILD { ... } }
     */
    if (code instanceof BlockStatement) {
      for (Statement sub : ((BlockStatement) code).getStatements()) {
        List<ClassNode> childResults = buildNodeClassesFromStatement(parent,
                                                                     sub);

        if (childResults == null) {
          continue;
        }

        for (ClassNode child : childResults) {
          if (child != null && child instanceof ClassNode) {
            childNodes.add(child);
          }
        }
      }
    } else

      /*
       * PARENT { ONLY_CHILD { ... } }
       */
      if (code instanceof ExpressionStatement) {
        ExpressionStatement es = (ExpressionStatement) code;
        Expression expression = es.getExpression();
        if (!(expression instanceof MethodCallExpression)) {
          addError("All Node Builder expressions must be method calls",
                   expression);
          return null;
        }
        ClassNode child = buildNodeClassFromExpression(parent,
                                                       (MethodCallExpression) expression);
        if (child != null) {
          childNodes.add(child);
        }
      } else {
        addError("Unsupported schema node", code);
      }
    return childNodes;
  }

  /**
   * Builds a node class from a MethodCallExpression. The expected syntax for
   * node definitions currently only has a few scenarios:
   * <ol>
   * 
   * <li>the node has one or more allowed children:
   * 
   * <pre>
   * 
   *    NODE {
   *      CHILD_1 { ... }
   *      CHILD_2 { ... }
   *    }
   * </pre>
   * 
   * <li>the node has no allowed children:
   * 
   * <pre>
   * 
   *    PARENT {
   *      NO_CHILDREN()
   *    }
   * </pre>
   * 
   * <li>the node has allowed children and allowed attributes:
   * 
   * <pre>
   * 
   *    NODE(["allowed-key-1", "allowed-key-2"]) {
   *      CHILD_1 { ... }
   *      CHILD_2 { ... }
   *    }
   * </pre>
   * 
   * <li>the node has no allowed children and allowed attributes:
   * 
   * <pre>
   * 
   *    PARENT {
   *      NO_CHILDREN(["allowed-key-1", "allowed-key-2"])
   *    }
   * </pre>
   * 
   * </ol>
   * 
   * @param cn
   * @param expression
   */
  private ClassNode buildNodeClassFromExpression(final ClassNode parent,
                                                 final MethodCallExpression expression) {

    String className = expression.getMethodAsString();

    /*
     * TODO: registry of nodes to allow recursive nodes? E.G.:
     * 
     * root { 
     *  repeatable_node { 
     *    some_node { 
     *      repeatable_node { 
     *        some_node { ... }
     *      } 
     *    } 
     *  }
     * }
     */
    ClassNode node = createNodeSubClass(parent, className);
    Expression tuple = expression.getArguments();

    /*
     * Evaluate arguments; this is where we want to determine what child nodes
     * are allowed and then recursively evaluate their allowed child nodes.
     * 
     * 
     * If there are no expressions then we just need to return the generated
     * node
     */
    if (tuple != null && tuple instanceof TupleExpression
        && ((TupleExpression) tuple).getExpressions() != null
        && !((TupleExpression) tuple).getExpressions().isEmpty()) {

      List<Expression> args = ((TupleExpression) tuple).getExpressions();
      if (args.size() > 1 
          || args.size() == 1
          && !(args.get(0) instanceof ClosureExpression)) {
        addError("Schema node definition must match the following method "
                 + "signature: (Closure allowedChildren = null)",
                 node);
        return null;
      }

      buildNodeClassesFromSchema(node, (ClosureExpression) args.get(0));
    }

    addMethodsToNode(className.toLowerCase(), parent, node);
    return node;
  }

  /**
   * NodeBuilder builder methods that need to be supported:
   * 
   * 1. element with child
   * 
   *  ELEMENT { CHILD() }
   * 
   * 2. element with value
   * 
   *  ELEMENT(Object text)
   * 
   * 3. element with value and attributes
   * 
   *  ELEMENT(Map attr, Object text)
   * 
   * 4. element with attributes and child
   * 
   *  ELEMENT(Map attr) { CHILD() }
   */
  private void addMethodsToNode(String methodName, ClassNode parentNode,
                                ClassNode childNode) {

    Expression ctorParent = (parentNode.equals(this.builderNode)) ? 
        ConstantExpression.NULL : new VariableExpression("this");
    ctorParent.setType(NODE_CLASS_NODE);
    Expression nameExpr = new ConstantExpression(methodName);

    Parameter closureArg = new Parameter(ClassHelper.CLOSURE_TYPE.getPlainNodeReference(),
                                         "code");

    ClassNode attributeMap = ClassHelper.MAP_TYPE.getPlainNodeReference();
    GenericsType[] gt = new GenericsType[] { 
      new GenericsType(ClassHelper.STRING_TYPE.getPlainNodeReference()),
      new GenericsType(ClassHelper.STRING_TYPE.getPlainNodeReference()) 
    };
    attributeMap.setGenericsTypes(gt);
    Parameter attributeArg = new Parameter(attributeMap, "attributes");
    Parameter textArg = new Parameter(ClassHelper.OBJECT_TYPE.getPlainNodeReference(),
                                      "text");

    /*
     * ELEMENT { CHILD() }
     * 
     * new Node(parent, "ELEMENT")
     */
    Parameter[] params = new Parameter[] { closureArg };
    ArgumentListExpression args = new ArgumentListExpression(ctorParent,
                                                             new ConstantExpression(methodName));
    parentNode.addMethod(makeMethodNodeWithChild(methodName,
                                                 params,
                                                 closureArg,
                                                 childNode,
                                                 args));

    /*
     * ELEMENT([attr: "val", attr2: "val2"]) { CHILD() }
     * 
     * new Node(parent, "ELEMENT", [attri: "val", attr2: "val2"])
     */
    params = new Parameter[] { attributeArg, closureArg };
    args = new ArgumentListExpression(ctorParent,
                                      nameExpr,
                                      new VariableExpression(attributeArg));
    parentNode.addMethod(makeMethodNodeWithChild(methodName,
                                                 params,
                                                 closureArg,
                                                 childNode,
                                                 args));

    /*
     * ELEMENT([attr: "val", attr2: "val2"], "inner text")
     * 
     * new Node(parent, "ELEMENT", [attri: "val", attr2: "val2"], "inner text")
     */
    params = new Parameter[] { attributeArg, textArg };
    args = new ArgumentListExpression(ctorParent,
                                      nameExpr,
                                      new VariableExpression(attributeArg));
    args.addExpression(new VariableExpression(textArg));
    parentNode.addMethod(makeMethodNodeWithoutChild(methodName,
                                                    params,
                                                    childNode,
                                                    args));

    /*
     * ELEMENT("InnerTextValue")
     * 
     * new Node(parent, "ELEMENT", "InnerTextValue")
     */
    params = new Parameter[] { textArg };
    args = new ArgumentListExpression(ctorParent,
                                      nameExpr,
                                      new VariableExpression(textArg));
    parentNode.addMethod(makeMethodNodeWithoutChild(methodName,
                                                    params,
                                                    childNode,
                                                    args));

    /*
     * ELEMENT()
     * 
     * new Node(parent, "ELEMENT")
     */
    params = Parameter.EMPTY_ARRAY;
    args = new ArgumentListExpression(ctorParent, nameExpr);
    parentNode.addMethod(makeMethodNodeWithoutChild(methodName,
                                                    params,
                                                    childNode,
                                                    args));

    /*
     * Force the child node definition closure to visit the delegation
     * transformation; while compilers don't seem to mind, this helps IDEs pick
     * up on the delegation chain.
     */
    AnnotationNode dtAnn = new AnnotationNode(ClassHelper.make(DelegatesTo.class));
    dtAnn.addMember("value", new ClassExpression(childNode));
    closureArg.addAnnotation(dtAnn);
    new DelegateASTTransformation().visit(new ASTNode[] { 
      dtAnn, closureArg 
    }, sourceUnit);
  }

  /**
   * Creates a method node with the following code:
   * 
   * ChildNodeClazz newNode = new ChildNodeClazz(args...)
   * closureArg.setDelegate(this) closureArg.call(this) return newNode
   * 
   * @param name
   *          the node name to use for the method name (e.g.: "html")
   * @param params
   *          the {@link Parameter[]} array
   * @param closureArg
   *          the {@link Closure} {@link Parameter} with the new node's child
   *          definitions that needs to be delegated to the instance of the
   *          child node
   * @param childNode
   *          the child {@link ClassNode}
   * @param args
   *          the argument list to be passed on to the <code>childNode</code>
   *          constructor
   * @return the generated {@link MethodNode}
   */
  private MethodNode makeMethodNodeWithChild(String name, Parameter[] params,
                                             Parameter closureArg,
                                             ClassNode childNode,
                                             ArgumentListExpression args) {
    BlockStatement code = new BlockStatement();
    Expression newNode = new VariableExpression("newNode", childNode);
    Expression closure = new VariableExpression(closureArg);
    code.addStatement(declS(newNode, ctorX(childNode, args)));
    code.addStatement(stmt(callX(closure,
                                 "setDelegate",
                                 args((new VariableExpression("newNode"))))));
    code.addStatement(stmt(callX(closure, "call")));
    code.addStatement(returnS(newNode));
    return makeMethodNode(name, params, childNode, code);
  }

  private MethodNode makeMethodNodeWithoutChild(String name,
                                                Parameter[] params,
                                                ClassNode childNode,
                                                ArgumentListExpression args) {
    return makeMethodNode(name,
                          params,
                          childNode,
                          returnS(ctorX(childNode, args)));
  }

  private MethodNode makeMethodNode(String name, Parameter[] params,
                                    ClassNode childNode, Statement code) {
    return new MethodNode(name,
                          ACC_PUBLIC,
                          NODE_CLASS_NODE,
                          params,
                          ClassNode.EMPTY_ARRAY,
                          code);
  }

  /**
   * Creates and returns a new {@link ClassNode} that extends the
   * {@link ConstrainedNode} class.
   * 
   * @param cn
   *          the parent class node
   * @param className
   *          the name of the class to generate
   * @return the generated {@link ConstrainedNode} extending {@link ClassNode}
   */
  private ClassNode createNodeSubClass(ClassNode cn, String className) {
    ClassNode subCn = new ClassNode(cn.getName()
                                        + "$"
                                        + StringGroovyMethods.capitalize(className),
                                    ACC_PUBLIC,
                                    NODE_CLASS_NODE);

    /*
     * ALERT: Certain versions of JDK (both Oracle and OpenJDK, as far as is
     * currently known) will generate a VerifyError due to a bug in the native
     * getDeclaredConstructors method. This is a problem here due to
     * groovy.util.Node, which seems to exhibit this behavior only when a
     * constructor is called from a sub-class using "super". Upgrading the JDK
     * version should resolve this issue.
     * 
     * OpenJDK: https://bugs.openjdk.java.net/browse/jdk-8051012 Oracle: ???
     */
    AnnotationNode compileStatic = new AnnotationNode(ClassHelper.make(CompileStatic.class));
    AnnotationNode inheritConstructors = new AnnotationNode(ClassHelper.make(InheritConstructors.class));
    subCn.addAnnotation(compileStatic);
    subCn.addAnnotation(inheritConstructors);
    new InheritConstructorsASTTransformation().visit(new ASTNode[] { 
      inheritConstructors, subCn 
    }, sourceUnit);
    
    sourceUnit.getAST().addClass(subCn);
    return subCn;
  }
}