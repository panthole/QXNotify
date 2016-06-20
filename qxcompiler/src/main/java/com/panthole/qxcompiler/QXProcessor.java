package com.panthole.qxcompiler;

import com.google.auto.service.AutoService;
import com.panthole.annotation.Receiver;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * Created by panlingyue on 2016/6/16.
 */
@AutoService(Processor.class) public class QXProcessor extends AbstractProcessor {

  Map<TypeElement, String> targetClassMap = new HashMap<TypeElement, String>();
  private Types typeUtils;
  private Elements elementUtils;
  private Filer filer;
  private Messager messager;
  private boolean isProcess = true;

  @Override public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    typeUtils = processingEnv.getTypeUtils();
    elementUtils = processingEnv.getElementUtils();
    filer = processingEnv.getFiler();
    messager = processingEnv.getMessager();
  }

  @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    if (isProcess) {
      TypeElement tempTypeElement = null;
      MethodSpec.Builder registerNotificationsMethod = null;
      MethodSpec.Builder unregisterNotificationsMethod = null;
      MethodSpec.Builder receiveNotificationsMethod = null;
      for (Element annotatedElement : env.getElementsAnnotatedWith(Receiver.class)) {
        TypeElement enclosingElement = (TypeElement) annotatedElement.getEnclosingElement();
        messager.printMessage(Diagnostic.Kind.NOTE,
            "Printing: " + enclosingElement.getSimpleName());

        if (annotatedElement.getKind() != ElementKind.METHOD) {
          error(annotatedElement, "Only methods can be annotated with @%s",
              Receiver.class.getSimpleName());
          return true; // Exit processing
        }
        Receiver annotation = annotatedElement.getAnnotation(Receiver.class);
        //generate code
        if (!targetClassMap.containsKey(enclosingElement)) {
          if (registerNotificationsMethod != null && tempTypeElement != null) {
            CodeBlock.Builder result = CodeBlock.builder();
            result.add("default:");
            result.add("break;}");
            receiveNotificationsMethod.addCode(result.build());

            TypeSpec typeCode =
                TypeSpec.classBuilder(tempTypeElement.getSimpleName() + "$$NotifyBinder")
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addTypeVariable(TypeVariableName.get("T",
                        TypeVariableName.get(tempTypeElement.getSimpleName() + "")))
                    .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get("com.iqiyi.ishow.notify", "NotifyBinder"),
                        TypeVariableName.get("T")))
                    .addMethod(registerNotificationsMethod.build())
                    .addMethod(unregisterNotificationsMethod.build())
                    .addMethod(receiveNotificationsMethod.build())
                    .build();

            JavaFile javaFile = JavaFile.builder(getPackageName(tempTypeElement), typeCode).build();

            try {
              javaFile.writeTo(processingEnv.getFiler());
            } catch (IOException e) {
              messager.printMessage(Diagnostic.Kind.ERROR, "Couldn't generate class");
            }
          }
          registerNotificationsMethod = MethodSpec.methodBuilder("registerNotifications")
              .addAnnotation(Override.class)
              .addModifiers(PUBLIC);
          registerNotificationsMethod.addParameter(TypeVariableName.get("T"), "target");
          registerNotificationsMethod.addStatement(
              ClassName.get("android.apps.fw", "NotificationCenter")
                  + ".getInstance().addObserver(target, "
                  + annotation.value()
                  + ")");

          unregisterNotificationsMethod = MethodSpec.methodBuilder("unregisterNotifications")
              .addAnnotation(Override.class)
              .addModifiers(PUBLIC);
          unregisterNotificationsMethod.addParameter(TypeVariableName.get("T"), "target");
          unregisterNotificationsMethod.addStatement(
              ClassName.get("android.apps.fw", "NotificationCenter")
                  + ".getInstance().addObserver(target, "
                  + annotation.value()
                  + ")");

          receiveNotificationsMethod = MethodSpec.methodBuilder("receiveNotifications")
              .addAnnotation(Override.class)
              .addModifiers(PUBLIC)
              .addException(IllegalAccessException.class)
              .addException(NoSuchMethodException.class)
              .addException(InvocationTargetException.class);
          receiveNotificationsMethod.addParameter(TypeVariableName.get("T"), "target");
          receiveNotificationsMethod.addParameter(TypeVariableName.get(int.class), "id");
          receiveNotificationsMethod.addParameter(TypeVariableName.get(Object[].class), "arg")
              .varargs();

          CodeBlock.Builder result = CodeBlock.builder();
          result.add("switch (id) {");
          result.add("case " + annotation.value() + ":{");
          result.addStatement(TypeVariableName.get(Method.class)
                  + " method = target.getClass().getDeclaredMethod($S, Object[].class)",
              annotatedElement.getSimpleName());
          result.addStatement("method.setAccessible(true)");
          result.addStatement("method.invoke(target,new Object[]{arg})");
          result.add("}");
          result.add("break;");
          receiveNotificationsMethod.addCode(result.build());

          targetClassMap.put(enclosingElement, "1");
          tempTypeElement = enclosingElement;
        } else {
          registerNotificationsMethod.addStatement(
              ClassName.get("android.apps.fw", "NotificationCenter")
                  + ".getInstance().addObserver(target, "
                  + annotation.value()
                  + ")");

          unregisterNotificationsMethod.addStatement(
              ClassName.get("android.apps.fw", "NotificationCenter")
                  + ".getInstance().removeObserver(target, "
                  + annotation.value()
                  + ")");

          CodeBlock.Builder result = CodeBlock.builder();
          result.add("case " + annotation.value() + ":{");
          result.addStatement(TypeVariableName.get(Method.class)
                  + " method = target.getClass().getDeclaredMethod($S, Object[].class)",
              annotatedElement.getSimpleName());
          result.addStatement("method.setAccessible(true)");
          result.addStatement("method.invoke(target,new Object[]{arg})");
          result.add("}");
          result.add("break;");
          receiveNotificationsMethod.addCode(result.build());
        }
      }

      CodeBlock.Builder result = CodeBlock.builder();
      result.add("default:");
      result.add("break;}");
      receiveNotificationsMethod.addCode(result.build());

      TypeSpec typeCode = TypeSpec.classBuilder(tempTypeElement.getSimpleName() + "$$NotifyBinder")
          .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
          .addTypeVariable(
              TypeVariableName.get("T", TypeVariableName.get(tempTypeElement.getSimpleName() + "")))
          .addSuperinterface(
              ParameterizedTypeName.get(ClassName.get("com.iqiyi.ishow.notify", "NotifyBinder"),
                  TypeVariableName.get("T")))
          .addMethod(registerNotificationsMethod.build())
          .addMethod(unregisterNotificationsMethod.build())
          .addMethod(receiveNotificationsMethod.build())
          .build();

      JavaFile javaFile = JavaFile.builder(getPackageName(tempTypeElement), typeCode).build();

      try {
        javaFile.writeTo(processingEnv.getFiler());
      } catch (IOException e) {
        messager.printMessage(Diagnostic.Kind.ERROR, "Couldn't generate class");
      }

      isProcess = false;
    }
    return true;
  }

  private void error(Element e, String msg, Object... args) {
    messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
  }

  private String getPackageName(TypeElement type) {
    return elementUtils.getPackageOf(type).getQualifiedName().toString();
  }

  @Override public Set<String> getSupportedAnnotationTypes() {
    Set<String> annotataions = new LinkedHashSet<String>();
    annotataions.add(Receiver.class.getCanonicalName());
    return annotataions;
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}