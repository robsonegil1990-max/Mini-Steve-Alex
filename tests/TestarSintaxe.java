import com.sun.source.util.JavacTask;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Confere sintaxe de todos os Java, sem depender do Forge para esta fase. */
public final class TestarSintaxe {
    private static void collect(File f, List<File> output) {
        if (!f.exists()) throw new IllegalArgumentException("Diretorio nao encontrado: " + f);
        if (f.isFile() && f.getName().endsWith(".java")) output.add(f);
        else if (f.isDirectory()) {
            File[] entries = f.listFiles();
            if (entries != null) for (File entry : entries) collect(entry, output);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Informe caminho da pasta Java");
        List<File> sourceFiles = new ArrayList<File>();
        collect(new File(args[0]), sourceFiles);
        if (sourceFiles.isEmpty()) throw new IllegalStateException("Nenhum .java encontrado");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Necessario um JDK, nao so JRE");
        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager manager = compiler.getStandardFileManager(problems, null, null);
        try {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromFiles(sourceFiles);
            JavacTask task = (JavacTask) compiler.getTask(null, manager, problems,
                    Arrays.asList("-proc:none", "-source", "8"), null, units);
            task.parse();
        } finally {
            manager.close();
        }
        int errors = 0;
        for (Diagnostic<? extends JavaFileObject> p : problems.getDiagnostics()) {
            if (p.getKind() == Diagnostic.Kind.ERROR) {
                errors++;
                System.err.println("ERRO " + p.getSource() + ":" + p.getLineNumber() + " " + p.getMessage(null));
            }
        }
        if (errors > 0) throw new IllegalStateException(errors + " erro(s) de sintaxe Java");
        System.out.println("OK: sintaxe Java 8 validada em " + sourceFiles.size() + " arquivos (nao valida APIs Forge).");
    }
}
