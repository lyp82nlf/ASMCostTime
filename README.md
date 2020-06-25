## ASM代码插桩监控方法耗时
####前言

我们会通过ASM+Transform 代码插桩来实现方法耗时监控 另外 用到了一个比较好用的插件 
[ASM Bytecode Outline](https://plugins.jetbrains.com/plugin/5918-asm-bytecode-outline) 这样在不会写操作码的时候 也可以去实现

我们主要分为三个部分来实现代码插桩

- 注解 (标记需要插桩的方法)
- Plugin+Transform实现代码扫描 寻找插桩点
- ASM实现代码生成注入

国际惯例 先贴个源码 [show me the code,no BB](https://github.com/lyp82nlf/ASMCostTime)

####注解

很简单,直接上代码 

```
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CostTime {
}
```

####Plugin+Transform
首先看一眼结构目录 有一个比较坑的点 坑了我半天的时间
![19207E7A-87EC-4EF0-B6EE-C8B7E7152855.png](https://upload-images.jianshu.io/upload_images/11006838-c5cc9001e632951e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

之前项目自动生成 是java 结果打出来的jar包 只包含了java文件 好气啊

####1.声明Plugin
```
public class CostTimePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.android.registerTransform(new CostTimeTransform())
    }
}
```

####2.实现Transform
已经将注释都写在代码中 直接看代码就可以 很简单易懂

```
package com.dsg.CostTImePlugin

import com.android.build.api.transform.Context
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.pipeline.TransformManager
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter


class CostTimeTransform extends Transform {

    @Override
    String getName() {
        //Transform名称
        return "CostTime"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(Context context, Collection<TransformInput> inputs, Collection<TransformInput> referencedInputs, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException, TransformException, InterruptedException {
        //遍历输入
        for (TransformInput input in inputs) {
            //遍历Directioy
            for (DirectoryInput dirInput in input.directoryInputs) {
                //处理需要插桩的文件
                modifyClassWithPath(dirInput.file)
                //Copy修改之后的文件
                File dest = outputProvider.getContentLocation(dirInput.name, dirInput.contentTypes,
                        dirInput.scopes, Format.DIRECTORY)
                FileUtils.copyDirectory(dirInput.file, dest)
            }
            //遍历JarInput 因为我们这里只对自己的方法插桩 所以不对JarInput做处理
            for (JarInput jarInput : input.jarInputs) {//jar（第三方库，module）
                if (jarInput.scopes.contains(QualifiedContent.Scope.SUB_PROJECTS)) {//module library
                    //从module中获取注解信息
//                    readClassWithJar(jarInput)
                }
                //虽然不做处理 但是还是要记得重新拷贝回去 不然会有问题
                copyFile(jarInput, outputProvider)
            }
        }
    }

    void modifyClassWithPath(File dir) {
        def root = dir.absolutePath
        dir.eachFileRecurse { File file ->
            def filePath = file.absolutePath
            //过滤非class文件
            if (!filePath.endsWith(".class")) return
            def className = getClassName(root, filePath)
            //过滤系统文件
            if (isSystemClass(className)) return
            //hook关键代码
            hookClass(filePath, className)
        }
    }

    void hookClass(String filePath, String className) {
        //1.声明ClassReader
        ClassReader reader = new ClassReader(new FileInputStream(new File(filePath)))
        //2声明 ClassWriter
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        //3声明ClassVisitor
        CostTimeMethodAdapter adapter = new CostTimeMethodAdapter(writer)
        //4调用accept方法 传入classVisitor
        reader.accept(adapter, ClassReader.EXPAND_FRAMES)
        if (adapter.changed) {
            println className + "is changed:" + adapter.changed
            byte[] bytes = writer.toByteArray()
            FileOutputStream fos = new FileOutputStream(new File(filePath))
            fos.write(bytes)
        }


    }


    //默认排除
    static final DEFAULT_EXCLUDE = [
            '^android\\..*',
            '^androidx\\..*',
            '.*\\.R$',
            '.*\\.R\\$.*$',
            '.*\\.BuildConfig$',
    ]

    //获取类名
    String getClassName(String root, String classPath) {
        return classPath.substring(root.length() + 1, classPath.length() - 6)
                .replaceAll("/", ".")       // unix/linux
                .replaceAll("\\\\", ".")    //windows
    }

    boolean isSystemClass(String fileName) {
        for (def exclude : DEFAULT_EXCLUDE) {
            if (fileName.matches(exclude)) return true
        }
        return false
    }
    void copyFile(JarInput jarInput, TransformOutputProvider outputProvider) {
        def dest = getDestFile(jarInput, outputProvider)
        FileUtils.copyFile(jarInput.file, dest)
    }

    static File getDestFile(JarInput jarInput, TransformOutputProvider outputProvider) {
        def destName = jarInput.name
        // 重名名输出文件,因为可能同名,会覆盖
        def hexName = DigestUtils.md5Hex(jarInput.file.absolutePath)
        if (destName.endsWith(".jar")) {
            destName = destName.substring(0, destName.length() - 4)
        }
        // 获得输出文件
        File dest = outputProvider.getContentLocation(destName + "_" + hexName, jarInput.contentTypes, jarInput.scopes, Format.JAR)
        return dest
    }

}
```

关键代码就在`hookClass`方法

####ASM插桩

简单讲一下ASM各类的作用

ClassReader会读取java字节码
ClassWriter通过toByteArray可以生成修改之后的字节码
ClassWriter是ClassVisitor的子类 我们一般会代理ClassWriter的实现
MethodVisitor 读取Method方法
MethodVisitor 的子类很多 具体可以参考 [ASM官网](https://asm.ow2.io/javadoc/overview-summary.html) 有很多不同功能的MethodVisitor

	我们会通过ClassReader读取字节码 然后通过ClassVisitor进行字节码的修改 
	然后再通过ClassWriter生成我们修改之后的字节码 
	大致的思路就是这样 接下来看一下源码
	
```
package com.dsg.CostTImePlugin;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.LocalVariablesSorter;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author DSG
 * @Project ASMCostTime
 * @date 2020/6/22
 * @describe
 */
public class CostTimeClassAdapter extends ClassVisitor {
    public boolean changed; //是否修改过 
    private String owner;
    private boolean isInterface;

    public CostTimeClassAdapter(ClassVisitor visitor) {
        super(ASM4, visitor);
    }

    public CostTimeClassAdapter(int api, ClassVisitor classVisitor) {
        super(api, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        cv.visit(version, access, name, signature, superName, interfaces);
        owner = name;
        isInterface = (access & ACC_INTERFACE) != 0;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!isInterface && mv != null && !name.equals("<init>")) {
            //将MethodVisitor交由CostTimeMethodAdapter代理
            mv = new CostTimeMethodAdapter(access, name, descriptor, mv);
        }
        return mv;

    }

    //继承自LocalVariablesSorter 有序遍历素有方法 
    class CostTimeMethodAdapter extends LocalVariablesSorter {
        private String name;
        private boolean isAnnotationed;
        private int time;

        public CostTimeMethodAdapter(int access, String name, String descriptor, MethodVisitor methodVisitor) {
            super(ASM4, access, descriptor, methodVisitor);
            this.name = name;
        }


        /**
         * 遍历代码的开始
         */
        @Override
        public void visitCode() {
            super.visitCode();
            if (isAnnotationed) {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                time = newLocal(Type.LONG_TYPE);
                mv.visitVarInsn(LSTORE, time);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            super.visitIntInsn(opcode, operand);
        }


        /**
         * 遍历操作码 判断是否是return语句 如果是return 就插入我们的代码
         *
         * @param opcode 操作码
         */
        @Override
        public void visitInsn(int opcode) {
            if ((opcode >= IRETURN && opcode <= RETURN) || opcode == ATHROW) {
                if (isAnnotationed) {
                    //这里的代码都可以由ASM插件生成
                    //Label可以生成局部变量
                    Label l1 = new Label();
                    mv.visitLabel(l1);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
                    mv.visitVarInsn(LLOAD, time);
                    mv.visitInsn(LSUB);
                    mv.visitVarInsn(LSTORE, 3);
                    Label l2 = new Label();
                    mv.visitLabel(l2);
                    mv.visitLdcInsn(owner);
                    mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                    mv.visitLdcInsn("func " + name + " cost Time:");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                    mv.visitVarInsn(LLOAD, 3);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", false);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                    mv.visitMethodInsn(INVOKESTATIC, "android/util/Log", "e", "(Ljava/lang/String;Ljava/lang/String;)I", false);
                }
            }
            super.visitInsn(opcode);
        }


        /**
         * @param descriptor 最先执行 判断是否存在注解 如果存在 就进行插桩
         * @param visible
         * @return
         */
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            isAnnotationed = ("Lcom/dsg/annotations/CostTime;".equals(descriptor));
            if (!changed && isAnnotationed) {
                changed = true;
            }
            return super.visitAnnotation(descriptor, visible);

        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
        }
    }
}

```

代码也写的很详细了 主要思路就是 

- 判断是否存在注解 是否需要插桩
- 在头部插入当前时间
- 在return之前计算方法耗时

####总结
其实ASM相对感觉还是比较简洁明了的 只要我们找好注入点 生成我们需要的代码就可以 还可以通过ASM插件来生成字节码 

感觉ASM+Transform的方式还是比较常见的 比如之前分析的Robust原理一样 而且ASM基本没有性能上的损耗 所以我们还是有必要深度学习一下


####参考的大佬的文章
[深入探索编译插桩技术（四、ASM 探秘）](https://juejin.im/post/5e8d87c4f265da47ad218e6b)

[使用javassist和ASM修改class，并实现方法耗时检测插件](https://juejin.im/post/5dea581fe51d45581d170b7c)
