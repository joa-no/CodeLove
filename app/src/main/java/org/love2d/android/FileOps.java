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

    static final String NEW_PROJECT_MAIN =
        "-- Projeto novo do CodeLove.\n"
        + "-- Escreva o seu jogo aqui.\n"
        + "\n"
        + "function love.load()\n"
        + "end\n"
        + "\n"
        + "function love.update(dt)\n"
        + "end\n"
        + "\n"
        + "function love.draw()\n"
        + "end\n";

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

    /**
     * Cria o projeto em uma pasta temporaria fora da arvore de projetos e so o
     * publica depois que main.lua estiver completamente gravado. Se qualquer
     * etapa falhar, tenta remover a pasta temporaria e nao deixa um projeto parcial.
     */
    static File createProject(File gamesDir, String name) {
        if (gamesDir == null || checkName(gamesDir, name) != null) {
            return null;
        }

        File staging = null;
        boolean published = false;
        try {
            File gamesRoot = gamesDir.getCanonicalFile();
            if (!gamesRoot.isDirectory()) {
                return null;
            }

            File project = new File(gamesRoot, name);
            if (!gamesRoot.equals(project.getCanonicalFile().getParentFile())
                || project.exists()) {
                return null;
            }

            File stagingRoot = gamesRoot.getParentFile();
            if (stagingRoot == null || !stagingRoot.isDirectory()) {
                return null;
            }

            // A pasta temporaria fica fora de games/, portanto nunca aparece na arvore.
            for (int attempt = 0; attempt < 10; attempt++) {
                staging = new File(stagingRoot,
                    ".codelove-project-" + System.nanoTime() + "-" + attempt + ".tmp");
                if (staging.mkdir()) {
                    break;
                }
                staging = null;
            }
            if (staging == null) {
                return null;
            }

            File main = new File(staging, "main.lua");
            if (!writeTextAndSync(main, NEW_PROJECT_MAIN)) {
                return null;
            }

            // Revalida o destino imediatamente antes da publicacao.
            if (project.exists()
                || !gamesRoot.equals(project.getCanonicalFile().getParentFile())
                || !staging.renameTo(project)) {
                return null;
            }

            published = true;
            return project;
        } catch (IOException e) {
            return null;
        } finally {
            if (!published && staging != null && staging.exists()) {
                deleteRecursive(staging);
            }
        }
    }

    /** Grava o arquivo inicial por completo antes de publicar a pasta do projeto. */
    private static boolean writeTextAndSync(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            return false;
        }
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
