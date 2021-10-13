package it.davidlab;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.davidlab.service.AlgoService;

import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Optional;

public class LambdaRequestHandler implements RequestHandler<Map<String, Object>, String> {

    Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public String handleRequest(Map<String, Object> iObject, Context context) {

        LambdaLogger logger = context.getLogger();

        logger.log( System.getenv("CORE_API_ADDR") +
                    System.getenv("CORE_API_PORT") +
                    System.getenv("CORE_API_TOKEN") +
                    System.getenv("INDEXER_API_ADDR") +
                    System.getenv("INDEXER_API_PORT") +
                    System.getenv("ACC_PASSPHRASE") +
                    System.getenv("ACC_ADDR"));

        AlgoService algoService;
        try {
            algoService = new AlgoService(
                    System.getenv("CORE_API_ADDR"),
                    Integer.parseInt(System.getenv("CORE_API_PORT")),
                    System.getenv("CORE_API_TOKEN"),
                    System.getenv("INDEXER_API_ADDR"),
                    Integer.parseInt(System.getenv("INDEXER_API_PORT")),
                    System.getenv("ACC_PASSPHRASE"),
                    System.getenv("ACC_ADDR"));
        }
        catch (GeneralSecurityException e) {
            logger.log(e.getMessage());
            throw new RuntimeException(e.getMessage());
        }

        logger.log("Algoservice init ok");

        Optional<Long> amountOpt = algoService.getAccountAmount();

        if (amountOpt.isEmpty()) {
            logger.log("Error getting amount");
            return "Error getting amount";
        }

        logger.log("Account amount: " + amountOpt.get());
        return "Account amount: " + amountOpt.get();

//        JsonElement jsonElement = gson.toJsonTree(iObject);
//        AssetModel assetModel = gson.fromJson(jsonElement, AssetModel.class);
//
//
//        try {
//            algoService.createAsset(assetModel);
//        }
//        catch (Exception e) {
//            throw new RuntimeException(e.getMessage());
//        }
//
//        return assetModel;



    }
}
