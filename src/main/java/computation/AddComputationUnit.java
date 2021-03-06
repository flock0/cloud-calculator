package computation;


public class AddComputationUnit implements ComputationUnit {

	@Override
	public ComputationResult compute(NodeRequest request) {
		int number = request.getOperand1() + request.getOperand2();
		return new ComputationResult(ResultStatus.OK, number);
	}

}
