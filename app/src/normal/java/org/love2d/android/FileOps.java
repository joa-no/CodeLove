/*
 * CodeLove - operacoes com arquivos e pastas dos projetos.
 * Nao depende do Android, so de Java comum.
 */

package org.love2d.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class FileOps {
    private static final int MAX_NAME = 64;

    private FileOps() {
    }

    /** Devolve a mensagem de erro se o nome nao servir, ou nulo se estiver tudo certo. */
    static String checkName(File folder, String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Digite um nome.";
        }
        if (name.length() > MAX_NAME) {
            return "Nome muito longo (maximo " + MAX_NAME + " letras).";
        }
        if (name.equals(".") || name.equals("..")) {
            return "Esse nome nao e permitido.";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) {
                return "O nome nao pode ter / \\ : * ? \" < > |";
            }
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            return "O nome nao pode terminar com ponto ou espaco.";
        }
        if (name.endsWith(".tmp")) {
            return "Nomes terminados em .tmp sao reservados.";
        }
        if (new File(folder, name).exists()) {
            return "Ja existe um item com esse nome.";
        }
        return null;
    }

    /** Arquivo sem extensao vira .lua (o caso mais comum no CodeLove). */
    static String withDefaultExtension(String name) {
        return name.indexOf('.') >= 0 ? name : name + ".lua";
    }

    /** Cria um arquivo vazio. Devolve nulo se falhar. */
    static File createFile(File folder, String name) {
        File file = new File(folder, name);
        try {
            return file.createNewFile() ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Cria uma pasta. Devolve nulo se falhar. */
    static File createFolder(File parent, String name) {
        File folder = new File(parent, name);
        return folder.mkdir() ? folder : null;
    }

    /** Move um item para outra pasta. Devolve o novo caminho, ou nulo se falhar. */
    static File move(File target, File destination) {
        if (target == null || destination == null || !destination.isDirectory()) {
            return null;
        }
        File dest = new File(destination, target.getName());
        if (dest.exists()) return null;
        return target.renameTo(dest) ? dest : null;
    }

    /** Apaga o arquivo, ou a pasta com tudo dentro. Devolve true se apagou tudo. */
    static boolean deleteRecursive(File target) {
        boolean ok = true;
        if (target.isDirectory() && !isSymlink(target)) {
            File[] kids = target.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    ok &= deleteRecursive(kid);
                }
            }
        }
        return target.delete() && ok;
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getCanonicalPath().equals(
                new File(file.getParentFile().getCanonicalFile(), file.getName()).getPath());
        } catch (IOException e) {
            return true; // na duvida, nao entra na pasta
        }
    }

    static boolean writeText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
