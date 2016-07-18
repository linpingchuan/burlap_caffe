package edu.brown.cs.burlap.vfa;

import burlap.behavior.functionapproximation.ParametricFunction;
import burlap.behavior.valuefunction.QProvider;
import burlap.behavior.valuefunction.QValue;
import burlap.mdp.core.action.Action;
import burlap.mdp.core.state.State;
import burlap.mdp.singleagent.environment.EnvironmentOutcome;
import edu.brown.cs.burlap.action.ActionSet;
import org.bytedeco.javacpp.FloatPointer;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.bytedeco.javacpp.caffe.*;

/**
 * Created by MelRod on 5/25/16.
 */
public class DQN implements ParametricFunction.ParametricStateActionFunction, QProvider, Serializable {

    /** If rewards are greater than this or less than the negative, they will be clipped */
    public static final int REWARD_CLIP = 1;

    /** The GPU device to use */
    public int gpuDevice = 0;

    /** The batch size of the network */
    public int batchSize;

    /** The input size of a single state */
    public int inputSize;

    /** The Caffe solver file */
    public String solverFile;

    /** The name of the state input layer in the Caffe net */
    public String stateInputLayerName = "state_input_layer";

    /** The name of the target input layer in the Caffe net */
    public String targetInputLayerName = "target_input_layer";

    /** The name of the action filter input layer in the Caffe net */
    public String filterInputLayerName = "filter_input_layer";

    /** The name of the q-value output blob in the Caffe net */
    public String qValuesBlobName = "q_values";

    /** An object to convert between BURLAP states and NN input */
    public StateVectorizor stateConverter;

    public double gamma;

    public ActionSet actionSet;


    public FloatNet caffeNet;
    protected FloatSolver caffeSolver;

    protected FloatMemoryDataLayer inputLayer;
    protected FloatMemoryDataLayer filterLayer;
    protected FloatMemoryDataLayer targetLayer;

    protected FloatPointer stateInputs;
    protected FloatPointer primeStateInputs;
    protected FloatPointer dummyInputData;
    protected FloatBlob qValuesBlob;

    public DQN(String caffeSolverFile, ActionSet actionSet, StateVectorizor stateConverter, double gamma) {
        this.solverFile = caffeSolverFile;
        this.actionSet = actionSet;
        this.stateConverter = stateConverter;
        this.gamma = gamma;

        constructNetwork();
    }

    protected DQN(DQN vfa) {
        this.gpuDevice = vfa.gpuDevice;

        this.actionSet = vfa.actionSet;
        this.stateConverter = vfa.stateConverter;
        this.gamma = vfa.gamma;
        this.inputSize = vfa.inputSize;

        this.solverFile = vfa.solverFile;
        this.stateInputLayerName = vfa.stateInputLayerName;
        this.targetInputLayerName = vfa.targetInputLayerName;
        this.filterInputLayerName = vfa.filterInputLayerName;
        this.qValuesBlobName = vfa.qValuesBlobName;

        constructNetwork();
        updateParamsToMatch(vfa);
    }

    protected void constructNetwork() {
        SolverParameter solver_param = new SolverParameter();
        ReadProtoFromTextFileOrDie(solverFile, solver_param);

        if (solver_param.solver_mode() == SolverParameter_SolverMode_GPU) {
            Caffe.set_mode(Caffe.GPU);
            Caffe.SetDevice(gpuDevice);
        } else {
            Caffe.set_mode(Caffe.CPU);
        }

        // construct the solver and network from file
        this.caffeSolver = FloatSolverRegistry.CreateSolver(solver_param);
        this.caffeNet = caffeSolver.net();

        // set the input layers
        this.inputLayer = new FloatMemoryDataLayer(caffeNet.layer_by_name(stateInputLayerName));
        this.filterLayer = new FloatMemoryDataLayer(caffeNet.layer_by_name(filterInputLayerName));
        this.targetLayer = new FloatMemoryDataLayer(caffeNet.layer_by_name(targetInputLayerName));

        // set local variables from network
        this.batchSize = inputLayer.batch_size();
        this.inputSize = inputLayer.width() * inputLayer.height() * inputLayer.channels();

        // create the data to store the inputs
        this.primeStateInputs = (new FloatPointer(batchSize * inputSize)).fill(0);
        this.stateInputs = (new FloatPointer(batchSize * inputSize)).fill(0);
        this.dummyInputData = (new FloatPointer(batchSize * inputSize)).fill(0);

        // set the qValues blob
        this.qValuesBlob = caffeNet.blob_by_name(qValuesBlobName);
    }

    public void updateQFunction(List<EnvironmentOutcome> samples, DQN staleVfa) {
        int sampleSize = samples.size();
        if (sampleSize < batchSize) {
            return;
        }

        // Fill in input arrays
        for (int i = 0; i < sampleSize; i++) {
            EnvironmentOutcome eo = samples.get(i);

            int pos = i * inputSize;
            stateConverter.vectorizeState(eo.o, stateInputs.position(pos));

            stateConverter.vectorizeState(eo.op, primeStateInputs.position(pos));
        }

        // Forward pass states
        staleVfa.inputDataIntoLayers(primeStateInputs.position(0), dummyInputData, dummyInputData);
        staleVfa.caffeNet.ForwardPrefilled();

        // Calculate target values
        int numActions = actionSet.size();
        FloatPointer ys = (new FloatPointer(sampleSize * numActions)).zero();
        FloatPointer actionFilter = (new FloatPointer(sampleSize * numActions)).zero();
        for (int i = 0; i < sampleSize; i++) {
            EnvironmentOutcome eo = samples.get(i);
            float maxQ = blobMax(staleVfa.qValuesBlob, i);

            // clip reward
            double r = eo.r;
            if (r > REWARD_CLIP) {
                r = REWARD_CLIP;
            } else if (r < -REWARD_CLIP) {
                r = -REWARD_CLIP;
            }

            float y;
            if (eo.terminated) {
                y = (float)r;
            } else {
                y = (float)(r + gamma*maxQ);
            }

            int index = i*numActions + actionSet.map(eo.a);
            ys.put(index, y);
            actionFilter.put(index, 1);
        }

        // Backprop
        inputDataIntoLayers(stateInputs.position(0), actionFilter, ys);
        caffeSolver.Step(1);
    }

    public float blobMax(FloatBlob blob, int n) {
        float max = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < blob.shape(1); c++) {
            float num = blob.data_at(n, c, 0, 0);
            if (max < num) {
                max = num;
            }
        }
        return max;
    }

    public void updateParamsToMatch(DQN vfa) {
        FloatBlobSharedVector params = this.caffeNet.params();
        FloatBlobSharedVector newParams = vfa.caffeNet.params();
        for (int i = 0; i < params.size(); i++) {
            params.get(i).CopyFrom(newParams.get(i));
        }
    }

    public FloatBlob qValuesForState(State state) {
        stateConverter.vectorizeState(state, stateInputs.position(0));
        inputDataIntoLayers(stateInputs, dummyInputData, dummyInputData);
        caffeNet.ForwardPrefilled();
        return qValuesBlob;
    }

    @Override
    public double evaluate(State state, Action action) {
        FloatBlob output = qValuesForState(state);

        int a = actionSet.map(action);
        return output.data_at(0,a,0,0);
    }

    @Override
    public List<QValue> qValues(State state) {

        FloatBlob qValues = qValuesForState(state);
        int numActions = actionSet.size();

        ArrayList<QValue> qValueList = new ArrayList<>(numActions);
        for (int a = 0; a < numActions; a++) {
            QValue q = new QValue(state, actionSet.get(a), qValues.data_at(0, a, 0, 0));
            qValueList.add(q);
        }

        return qValueList;
    }

    @Override
    public double qValue(State state, Action abstractGroundedAction) {
        return evaluate(state, abstractGroundedAction);
    }

    @Override
    public double value(State s) {
        List<QValue> qs = this.qValues(s);
        double max = Double.NEGATIVE_INFINITY;
        for(QValue q : qs){
            max = Math.max(max, q.q);
        }
        return max;
    }

    protected void inputDataIntoLayers(FloatPointer inputData, FloatPointer filterData, FloatPointer yData) {
        inputLayer.Reset(inputData, dummyInputData, batchSize);
        filterLayer.Reset(filterData, dummyInputData, batchSize);
        targetLayer.Reset(yData, dummyInputData, batchSize);
    }

    /** Saves the caffe solver state and weights */
    public void saveLearningState(String filePrefix) {
        caffeSolver.Snapshot();

        // get file names
        int iter = caffeSolver.iter();
        String modelFile = String.format("_iter_%d.caffemodel", iter);
        String solverFile = String.format("_iter_%d.solverstate", iter);

        // move Caffe files
        try {
            Files.move(Paths.get(modelFile), Paths.get(filePrefix + ".caffemodel"), REPLACE_EXISTING);
            Files.move(Paths.get(solverFile), Paths.get(filePrefix + ".solverstate"), REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadLearningState(String filePrefix) {
        caffeSolver.Restore(filePrefix + ".solverstate");
    }

    public void loadWeightsFrom(String fileName) {
        caffeNet.CopyTrainedLayersFrom(fileName);
    }

    @Override
    public ParametricFunction copy() {
        return new DQN(this);
    }


    // Unsupported Operations
    @Override
    public double getParameter(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setParameter(int i, double v) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int numParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resetParameters() {
        throw new UnsupportedOperationException();
    }
}