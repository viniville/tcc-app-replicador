package com.replicador;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

public class ReplicadorGit {
    private static Repository repoOriginal;
    private static Repository repoCopy;
    private static StringBuilder sbFinal;
    private static String nameBranchHead;

    public static void run(File dirGit, File dirBaseReplica) throws IOException, GitAPIException {
        repoOriginal = null;
        repoCopy = null;
        sbFinal = new StringBuilder();
        nameBranchHead = null;

        final Instant start = Instant.now();
        openRepository(dirGit);
        //Inicializa repositorio a ser replicado
        initRepository(dirBaseReplica.getAbsolutePath() + File.separator + dirGit.getName());

        //Cria Branches do original no repositório a ser copiado
        createBranches();

        //Rebuild do repositorio
        System.out.println("********************************************************");
        System.out.println("INICIO PROCESSO GIT - Repositorio: " + dirGit.getName());
        System.out.println("********************************************************");
        rebuildRepository();
        Duration tempoExecucao = Duration.between(start, Instant.now());
        System.out.println("********************************************************");
        System.out.println("FIM PROCESSO GIT - Repositorio: " + dirGit.getName());
        System.out.println("********************************************************");
        try (PrintWriter out = new PrintWriter(dirBaseReplica.getAbsolutePath() + File.separator
                + dirGit.getName() + ".txt")) {
            out.println(sbFinal.toString());
            out.println(String.format("Duração: %s (em segundos); %s (em minutos)%n", tempoExecucao.getSeconds(),
                    tempoExecucao.getSeconds() / 60));
        }
    }

    private static void createBranches() throws IOException, GitAPIException {
        try (Git git = new Git(repoOriginal);
             Git gitCopy = new Git(repoCopy)) {

            File myfile = new File(repoCopy.getDirectory().getParent(), "_readme");
            myfile.createNewFile();

            // run the add
            gitCopy.add().addFilepattern("_readme").call();

            // and then commit the changes
            RevCommit call = gitCopy
                    .commit()
                    .setMessage("Arquivo fake para possibilitar criação dos branches")
                    .call();

            File fileMaster = new File(repoCopy.getDirectory() + "/refs/heads",
                    nameBranchHead);
            fileMaster.createNewFile();

            try (PrintWriter pw = new PrintWriter(fileMaster)) {
                pw.write(call.getName());
            }

            List<Ref> refs = git.branchList().call();
            for (Ref ref : refs) {
                String name = ref.getName().replace("refs/heads/", "");
                if (!name.contains(Constants.MASTER) && !name.contains("main") && !name.contains("unstable")) {
                    gitCopy.branchCreate().setName(name).setStartPoint(call).call();
                }
            }
        }
    }

    private static void initRepository(String diretorio)
            throws GitAPIException, IOException {

        File dir = new File(diretorio);
        deleteDirectory(dir);
        dir.mkdirs();

        Git.init().setDirectory(dir).setInitialBranch(nameBranchHead).call();

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repoCopy = builder.setGitDir(new File(diretorio + "/.git"))
                .readEnvironment() // scan environment GIT_* variables
                .findGitDir() // scan up the file system tree
                .build();
    }

    private static void rebuildRepository() throws RevisionSyntaxException, IOException, GitAPIException {

        Git gitCopy = new Git(repoCopy);
        Git gitOriginal = new Git(repoOriginal);
        Ref head = repoOriginal.getRefDatabase().findRef("HEAD");
        RevWalk walk = new RevWalk(repoOriginal);
        walk.sort(RevSort.REVERSE, true);
        RevCommit commit = walk.parseCommit(head.getObjectId());
        walk.markStart(commit);

        RevCommit rev;
        int i = 0;
        int countBranche = 0;

        //Iterar sobre os commits do repositório (master/main - HEAD)
        while ((rev = walk.next()) != null) {

            if (i++ == 0)
                continue;

            //Para cada commit verificar se tem mais que um pai/parent
            //Quando tiver mais que 1, significa que é um arquivo mergeado
            if (rev.getParentCount() > 1) {
                var idParents = Arrays.stream(rev.getParents())
                        .map(AnyObjectId::getName)
                        .collect(Collectors.joining(","));
                System.out.printf("ID: %s; ParentCount: %s; Parents id: [%s]; Author: %s%n", rev.getName(),
                        rev.getParentCount(), idParents, rev.getAuthorIdent());

                List<Ref> listaBranches = new ArrayList<>(0);

                //Buscar os arquivos fontes de cada um dos commits pais
                RevCommit[] parents = rev.getParents();
                for (RevCommit revCommit : parents) {

                    ObjectId id = repoOriginal.resolve(revCommit.getName());
                    RevCommit revCommitComplete = walk.parseCommit(id);

                    String strBranche = "branch-" + ++countBranche;

                    //criar um branche temporario e efetua checkout - repositorio copia
                    CheckoutCommand checkout = gitCopy.checkout();
                    checkout.setName(strBranche);
                    checkout.setCreateBranch(true);
                    checkout.call();

                    Ref branche = repoCopy.getRefDatabase().findRef(strBranche);

                    // Obter arquivos do revCommitComplete e comitar neste novo branche criado anteriormente
                    buscarComitarArquivosRevisao(gitCopy, gitOriginal,
                            revCommitComplete, getFromBranch(rev), branche, true);
                    //Efetua checkout no master/main para quando criar o outro branche crie a partir do master
                    checkoutBranche(gitCopy, null);
                    listaBranches.add(branche);
                }

                if (listaBranches.isEmpty())
                    continue;

                //Efetuar o merge do repositório diferente do master para o master
                efetuaMerges(gitCopy, listaBranches);
                gitCopy.merge().setStrategy(MergeStrategy.RECURSIVE);
                //Busca a maior revisao do repositorio copy (git log -n 1)
                ObjectId idPreviousLast = repoCopy.resolve("HEAD");
                RevWalk walkPreviousLastCommit = new RevWalk(repoCopy);
                RevCommit previousLastCommit = walkPreviousLastCommit
                        .parseCommit(idPreviousLast);

                //Deve efetuar commit dos arquivos originais, para comparar esta versao com a anterior
                Map<String, String> mapOriginais = buscaArquivosFontes(rev,
                        repoOriginal);

                //Checkout para o master na copia
                checkoutBranche(gitCopy, null);

                //Itera sobre a lista de arquivos original
                for (Entry<String, String> regMap : mapOriginais.entrySet()) {
                    //pula arquivos sem conteudo
                    if (regMap.getKey().equals("/dev/null") && (regMap.getValue() == null || regMap.getValue().isBlank()))
                        continue;

                    //Cria arquivo caso nao exista
                    File myFile = new File(repoCopy.getDirectory().getParent()
                            + File.separator + regMap.getKey());
                    myFile.getParentFile().mkdirs();
                    myFile.createNewFile();

                    escreveArquivo(myFile, mapOriginais.get(regMap.getKey()));

                    //Adiciona alteracoes - git add
                    gitCopy.add().addFilepattern(regMap.getKey()).call();
                }
                Status status = gitCopy.status().call();
                if (!status.getUncommittedChanges().isEmpty() || !status.getUntracked().isEmpty()) {
                    //Efetua commit das alteracoes
                    RevCommit lastCommit;
                    try {
                        lastCommit = gitCopy.commit().setAll(true)
                                .setMessage("Commit ").call();
                    } catch (Throwable e) {
                        if (e.getCause() instanceof EOFException) {
                            executaArquivoCommitExterno(gitCopy.getRepository().getDirectory()
                                    .getParentFile().getAbsolutePath());
                            repoCopy.scanForRepoChanges();
                            ObjectId objHead = repoCopy.resolve("HEAD");
                            lastCommit = new RevWalk(repoCopy).parseCommit(objHead);
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                    // Compara versao anterior com a atual
                    escreveLinhasAlteracao(previousLastCommit, lastCommit);
                }
            } else {
                Ref brancheAux = getFromBranch(rev);
                buscarComitarArquivosRevisao(gitCopy, gitOriginal, rev,
                        brancheAux, brancheAux, false);
                System.out.printf("ID: %s; Parent id: [%s]; Author: %s%n", rev.getName(),
                        Optional.ofNullable(rev.getParents())
                                .map(parent -> parent.length > 0 ? parent[0] : null)
                                .map(AnyObjectId::getName)
                                .orElse("null"),
                        rev.getAuthorIdent());
            }
        }
    }

    private static void escreveLinhasAlteracao(RevCommit rev, RevCommit commit) {
        try {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                AbstractTreeIterator oldTreeParser = prepareTreeParser(repoCopy,
                        rev.getId());
                AbstractTreeIterator newTreeParser = prepareTreeParser(repoCopy,
                        commit.getId());

                Map<String, String> fontes = buscaArquivosFontes(commit, repoCopy);

                // then the procelain diff-command returns a list of diff entries
                List<DiffEntry> diff = new Git(repoCopy).diff()
                        .setOldTree(oldTreeParser).setNewTree(newTreeParser)
                        .call();
                for (DiffEntry entry : diff) {
                    if (!entry.getChangeType().equals(ChangeType.MODIFY)
                            && !entry.getChangeType().equals(ChangeType.COPY))
                        continue;

                    String[] qtdLinhas = fontes.get(entry.getNewPath()).split(System.lineSeparator());

                    DiffFormatter formatter = new DiffFormatter(output);
                    formatter.setRepository(repoCopy);
                    formatter.format(entry);

                    StringBuilder sb = new StringBuilder();
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(output.toByteArray());
                         BufferedReader bfr = new BufferedReader(new InputStreamReader(bais))) {
                        String line;
                        while ((line = bfr.readLine()) != null)
                            sb.append(line).append(System.lineSeparator());
                    }
                    sbFinal.append(entry.getNewPath())
                            .append(";")
                            .append(qtdLinhas.length)
                            .append(";")
                            .append(countMais(sb.toString()))
                            .append(";")
                            .append(countMenos(sb.toString()))
                            .append("\n");
                }
            }
        } catch (Exception e) {
            System.out.println("Erro ao executar método escreveLinhasAlteracao.\n" + e.getMessage());
        }
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException {
        RevWalk walk = new RevWalk(repository);
        RevCommit commit = walk.parseCommit(objectId);
        RevTree tree = walk.parseTree(commit.getTree().getId());

        CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
        try (ObjectReader oldReader = repository.newObjectReader()) {
            oldTreeParser.reset(oldReader, tree.getId());
        }
        return oldTreeParser;
    }

    private static void escreveArquivo(File file, String conteudo)
            throws IOException {
        if ((file.getName().equals("/dev/null") && (conteudo == null || conteudo.isBlank())) || file.isDirectory())
            return;

        if (!file.exists())
            file.createNewFile();

        try (PrintWriter pw = new PrintWriter(file)) {
            pw.write(conteudo);
        }
    }

    private static void efetuaMerges(Git gitCopy, List<Ref> listaBranches) throws GitAPIException {
        if (listaBranches == null || listaBranches.isEmpty())
            return;

        //Faz checkout para o master
        CheckoutCommand coCmd = gitCopy.checkout();
        coCmd.setName(nameBranchHead);
        coCmd.setCreateBranch(false);
        coCmd.call();

        String diretorio = gitCopy.getRepository().getDirectory()
                .getParentFile().getAbsolutePath();

        for (Ref ref : listaBranches) {
            String nomeBranche = ref.getName();
            String replace = nomeBranche.replace("refs/heads/", "");
            executaArquivoMerge(diretorio, replace);
        }
    }

    private static void buscarComitarArquivosRevisao(Git gitCopy,
                                                     Git gitOriginal, RevCommit rev,
                                                     Ref brancheOriginal,
                                                     Ref brancheAComitar,
                                                     boolean revisaoMerge) throws IOException, GitAPIException {
        // Checkout branche original
        checkoutBranche(gitOriginal, brancheOriginal);

        // Checkout branche copia
        checkoutBranche(gitCopy, brancheAComitar);

        // Buscar os arquivos fontes do commit
        Map<String, String> map = buscaArquivosFontes(rev, repoOriginal);

        if (revisaoMerge) {
            var strFontes = String.join(",", map.keySet());
            System.out.printf("Arquivos commit parent Id %s : [%s]%n",
                    rev.getName(), strFontes);
        }
        if (map.isEmpty())
            return;

        // Comitá-los em seu respectivo branche
        for (Entry<String, String> regMap : map.entrySet()) {

            if (regMap.getKey().equals("/dev/null") && (regMap.getValue() == null || regMap.getValue().isBlank()))
                continue;

            // Criar arquivo
            File myFile = new File(repoCopy.getDirectory().getParent()
                    + File.separator + regMap.getKey());
            if (myFile.isDirectory()) {
                continue;
            }
            if (myFile.getParentFile().exists() && !myFile.getParentFile().isDirectory()) {
                System.out.printf("ATENÇÃO! Identificado aquivo com mesmo nome do diretorio parent " +
                                "que deve ser criado. Rev: %s, Arquivo: %s.\nTentando deletar arquivo -> ", rev.getName(),
                        myFile.getParentFile());
                System.out.println(myFile.getParentFile().delete() ? "Deletado com sucesso!" : "Falha ao remover!");
            }
            myFile.getParentFile().mkdirs();
            if (myFile.exists() && !myFile.isFile()) {
                System.out.printf("ATENÇÃO! Identificado diretorio com mesmo nome do arquivo " +
                                "que deve ser criado. Rev: %s, Diretorio: %s.\nTentando remover diretorio -> ",
                        rev.getName(), myFile);
                System.out.println(myFile.delete() ? "Removido com sucesso!" : "Falha ao remover!");
            }
            myFile.createNewFile();

            // Copiar o conteudo
            try (PrintWriter pw = new PrintWriter(myFile)) {
                pw.write(regMap.getValue());
            }
            // Adicionar ao repositório
            gitCopy.add().addFilepattern(regMap.getKey()).call();
        }
        gitCopy.commit().setMessage(rev.getFullMessage()).call();
    }

    private static void checkoutBranche(Git git, Ref branche) throws GitAPIException {

        // Efetua checkout da revisão
        CheckoutCommand checkout = git.checkout();
        if (branche == null)
            checkout.setName(nameBranchHead);
        else {
            checkout.setName(branche.getName().replace("refs/heads/", ""));
        }
        checkout.setCreateBranch(false);
        checkout.call();
    }

    public static Ref getFromBranch(RevCommit commit) {

        try {
            ListBranchCommand branchList = new Git(repoOriginal).branchList();
            branchList.setContains(commit.getName());
            List<Ref> call = branchList.call();
            if (call == null)
                return null;

            for (Ref ref : call) {
                String name = ref.getName().replace("refs/heads/", "");
                if (name.contains(Constants.MASTER) || name.contains("main"))
                    continue;
                return ref;
            }

        } catch (Exception e) {
            System.out.println("Erro ao  buscar branche");
        }
        return null;
    }

    private static Map<String, String> buscaArquivosFontes(RevCommit rev, Repository repoOriginal) throws IOException {
        Map<String, String> mapRetorno = new HashMap<>(0);
        RevWalk rw = new RevWalk(repoOriginal);

        if (rev.getParentCount() == 0)
            return mapRetorno;

        RevCommit parent = rw.parseCommit(rev.getParent(0).getId());
        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(repoOriginal);
        df.setDiffComparator(RawTextComparator.DEFAULT);
        df.setDetectRenames(true);
        List<DiffEntry> diffs = df.scan(parent.getTree(), rev.getTree());
        for (DiffEntry diff : diffs) {
            if (!validExtensionFiles(diff.getNewPath()))
                continue;
            try {
                RevTree tree = rev.getTree();
                // now try to find a specific file
                TreeWalk treeWalk = new TreeWalk(repoOriginal);
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(diff.getNewPath()));
                if (!treeWalk.next()) {
                    mapRetorno.put(diff.getNewPath(), "");
                    continue;
                }

                ObjectId objectId = treeWalk.getObjectId(0);
                ObjectLoader loader = repoOriginal.open(objectId);

                String source = new String(loader.getBytes());
                mapRetorno.put(diff.getNewPath(), source);
            } catch (Exception e) {
                System.out.println("Erro:" + e.getMessage());
            }

        }

        return mapRetorno;
    }

    private static boolean validExtensionFiles(String filename) {
        var invalidExtensions = List.of("jar", "zip", "7zip", "rar", "tar", "svg", "png", "jpg", "exe", "msi",
                "ico", "bmp", "so", "ttf", "xls", "xlsx", "doc", "docx", "ppt", "pptx", "pdf");
        if (filename != null && !filename.isBlank()) {
            int dotIdx = filename.lastIndexOf(".");
            if (dotIdx > 0) {
                final var extFile = filename.substring(dotIdx + 1).toLowerCase();
                return !invalidExtensions.contains(extFile);
            }
        }
        return true;
    }

    private static void openRepository(File dir) throws IOException {
        repoOriginal = new FileRepositoryBuilder()
                .setGitDir(new File(dir.getAbsolutePath() + "/.git"))
                .readEnvironment() // scan
                // environmenthead
                // GIT_*d
                // variables
                .findGitDir() // scan up the file system tree
                .build();
        Ref head = repoOriginal.getRefDatabase().findRef("HEAD");
        nameBranchHead = head.getTarget().getName().replace("refs/heads/", "");
    }

    private static void executaArquivoMerge(String diretorio, String branche) {
        // Caso o arquivo não exista fisicamente
        String arquivo = "./gitMerge.sh";
        if (!new File(arquivo).exists())
            return;

        try {
            String comando = String.join(" ", arquivo, diretorio, nameBranchHead, branche);
            System.out.println("Executando comando [" + comando + "].");

            // Executa o processo
            Process p = Runtime.getRuntime().exec(comando);

            // Aguarda até que o processo seja executado
            p.waitFor();

            // Obtém o retorno do processo
            //int r = p.exitValue();
            //System.out.println(String.format("Processo executado com %s", r == 0 ? "sucesso": "falha"));
			//if (r != 0) {
			//	System.out.println("erro ao executar script de merge");
			//}
            //System.out.println("Retorno: " + r);

            // Força a finalização do processo
            p.destroy();

        } catch (Exception e) {
            System.out.println("Não foi possível executar o arquivo ["
                    + arquivo + "].");
        }
    }

    /**
     * Identificado problema para comitar alguns arquivos pelo plugin, nestes casos fazemos
     * o commit chamando o script criado na pasta do projeto.
     */
    private static void executaArquivoCommitExterno(String diretorio) {
        // Caso o arquivo não exista fisicamente
        String arquivo = "./gitCommit.sh";
        if (!new File(arquivo).exists())
            return;

        try {
            String comando = String.join(" ", arquivo, diretorio, "Commit");
            System.out.println("Executando comando [" + comando + "].");

            // Executa o processo
            Process p = Runtime.getRuntime().exec(comando);

            // Aguarda até que o processo seja executado
            p.waitFor();

            // Obtém o retorno do processo
            int r = p.exitValue();
            if (r != 0) {
                System.out.println("erro ao executar script de commit");
            }
            //System.out.println("Retorno: " + r);

            // Força a finalização do processo
            p.destroy();

        } catch (Exception e) {
            System.out.println("Não foi possível executar o arquivo ["
                    + arquivo + "].");
        }
    }

    public static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            assert children != null;
            for (String child : children) {
                boolean success = deleteDirectory(new File(dir, child));
                if (!success) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    public static int countMais(String arquivo) {
        String[] arrayTeste = arquivo.split(System.lineSeparator());
        int countMais = 0;
        for (String string : arrayTeste) {
            if (string.startsWith("+++"))
                continue;

            if (string.startsWith("+"))
                countMais++;
        }
        return countMais;
    }

    public static int countMenos(String arquivo) {
        String[] arrayTeste = arquivo.split(System.lineSeparator());
        int countMenos = 0;
        for (String string : arrayTeste) {
            if (string.startsWith("---"))
                continue;

            if (string.startsWith("-"))
                countMenos++;
        }
        return countMenos;
    }

}
