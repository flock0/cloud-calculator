package util;

public class UnsupportedComputationUnit implements ComputationUnit {

	@Override
	public ComputationResult compute(String[] request) {
		return new ComputationResult(ResultStatus.OperatorNotSupported, 0);
	}

}
