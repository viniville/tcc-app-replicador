package com.replicador;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.replicador.SamplesUtility.crateIfNotExists;

public class Main {

    private static enum TypeVCS {
        GIT, SVN
    }

    private static final int IDX_PARAM_TYPE_CVS = 0;
    private static final int IDX_PARAM_SOURCE_GIT_PROJECTS = 1;
    private static final int IDX_PARAM_REPLICA_DESTINATION_REPOSITORY = 2;

    public static void main(String[] args) throws IOException {
        if (validaInputParams(args)) {
            final var typeRepository = TypeVCS.valueOf(args[IDX_PARAM_TYPE_CVS].toUpperCase());
            final var sourceRepository = args[IDX_PARAM_SOURCE_GIT_PROJECTS];
            final var targetRepository = args[IDX_PARAM_REPLICA_DESTINATION_REPOSITORY];

            File directoryReposOriginais = new File(sourceRepository);
            if (directoryReposOriginais.exists() && directoryReposOriginais.isDirectory()) {
                final File[] directories = directoryReposOriginais.listFiles(File::isDirectory);
                assert directories != null;
                File dirBaseReplicas = createReposReplicasIfNotExists(targetRepository);
                Arrays.stream(directories).forEach(dirProcess -> processDirectory(typeRepository, dirProcess, dirBaseReplicas));
            }
        }
    }

    private static boolean validaInputParams(String[] args) {
        if (args == null || args.length == 0 || List.of("--help", "--h").contains(args[0].toLowerCase())) {
            System.out.println(getHelpStringParamsExec());
            return false;
        }
        if (args.length < 3) {
            System.out.println("Parameters required for execution not provided.\n" + getHelpStringParamsExec());
            return false;
        }
        if (!List.of("git", "svn").contains(args[IDX_PARAM_TYPE_CVS].toLowerCase())) {
            System.out.println("First parameter to be \"git\" or \"svn\".\n");
            return false;
        }
        if (args[IDX_PARAM_SOURCE_GIT_PROJECTS] == null 
                || args[IDX_PARAM_SOURCE_GIT_PROJECTS].trim().isEmpty() 
                || !(new File(args[IDX_PARAM_SOURCE_GIT_PROJECTS])).exists()) {
            System.out.println("Repository containing the source git projects does not exist");
            return false;
        }
        return true;
    }

    private static String getHelpStringParamsExec() {
        return """
                required params: [git | svn] [project-source-directory] [project-destination-directory]
                \tproject-source-directory : repository containing the source git projects
                \tproject-destination-directory : destination repository where replicas of the original repositories will be created
                ex: git "/temp/base/projects" "/temp/target/projects\"
                """;
    }

    private static File createReposReplicasIfNotExists(String pathReplicas) {
        File dirReplica = new File(pathReplicas);
        crateIfNotExists(dirReplica);
        return dirReplica;
    }

    private static void processDirectory(TypeVCS typeRepository, File dirProcess, File dirBaseReplicas) {
        try {
            if (TypeVCS.GIT.equals(typeRepository)) {
                ReplicadorGit.run(dirProcess, dirBaseReplicas);
            } else if (TypeVCS.SVN.equals(typeRepository)) {
                ReplicadorSvn2.run(dirProcess, dirBaseReplicas);
            }
        } catch (Exception e) {
            System.out.printf("Não foi possível concluir o processamento do repo %s%n", dirProcess.getName());
        }
    }

}