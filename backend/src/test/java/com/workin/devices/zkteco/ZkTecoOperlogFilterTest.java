package com.workin.devices.zkteco;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ZkTecoOperlogFilterTest {

	@Test
	void keepsOperationsCountsUsersAndDiscardsEveryTemplateKind() {
		String body = String.join("\r\n",
				"OPLOG 13\t0\t2024-03-12 11:03:28\t0\t0\t0\t0",
				"USER PIN=1001\tName=Ahmed\tPri=0\tPasswd=\tCard=\tGrp=1\tTZ=0",
				"FP PIN=1001\tFID=0\tSize=1024\tValid=1\tTMP=U0VDUkVU",
				"FACE PIN=1001\tFID=0\tSIZE=2048\tVALID=1\tTMP=U0VDUkVU",
				"BIODATA Pin=1001\tNo=0\tIndex=0\tType=9\tTmp=U0VDUkVU",
				"USERPIC PIN=1001\tSize=99\tContent=U0VDUkVU",
				"BIOPHOTO PIN=1001\tSize=99\tContent=U0VDUkVU",
				"OPLOG 0\t0\t2024-03-12 11:03:48",
				"WHATEVER this is new",
				"");

		ZkTecoOperlogFilter.Result result = ZkTecoOperlogFilter.filter(body);

		assertThat(result.operationLines()).containsExactly(
				"OPLOG 13\t0\t2024-03-12 11:03:28\t0\t0\t0\t0", "OPLOG 0\t0\t2024-03-12 11:03:48");
		assertThat(result.userLines()).isEqualTo(1);
		assertThat(result.biometricLinesDiscarded()).isEqualTo(5);
		assertThat(result.otherLines()).isEqualTo(1);
		assertThat(String.join("\n", result.operationLines())).doesNotContain("U0VDUkVU").doesNotContain("Ahmed");
	}

	@Test
	void theRecordKindIsCaseInsensitive() {
		assertThat(ZkTecoOperlogFilter.filter("fp PIN=1\tTMP=x").biometricLinesDiscarded()).isEqualTo(1);
		assertThat(ZkTecoOperlogFilter.filter("oplog 1\t0").operationLines()).hasSize(1);
	}
}
