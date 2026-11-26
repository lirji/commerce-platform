import { Spin } from "antd";
import { encode, useResource } from "../shared/api";
import {
  JourneyHistoryFacts,
  type JourneyHistory,
} from "../shared/JourneyHistoryFacts";
import { useCursorState } from "../shared/routeState";
import { ErrorNotice, Pager } from "../shared/ui";

const cursor = "LegacyJourneyHistory.after";

export function LegacyJourneyHistory({ instanceId }: { instanceId: string }) {
  const [after, setAfter] = useCursorState(cursor, -1, instanceId);
  const history = useResource<JourneyHistory>(
    `/admin/journey-instances/${encode(instanceId)}/history?afterVersion=${after}&limit=50`,
  );
  const result = history.data;
  const matches =
    result?.instance?.instanceId === instanceId &&
    result.definition?.journeyId === result.instance.journeyId &&
    result.definition.version === result.instance.journeyVersion;
  return (
    <section aria-label="原实例执行记录">
      <ErrorNotice
        error={
          history.error ??
          (result && !matches
            ? new Error("返回结果与原实例或固定版本不一致")
            : undefined)
        }
        onRetry={history.refresh}
      />
      {history.loading && <Spin description="正在读取实例历史" />}
      {matches && !history.error && (
        <>
          <JourneyHistoryFacts history={result} />
          <Pager
            after={after}
            initial={-1}
            cursorName={cursor}
            count={result.steps.length}
            loading={history.loading}
            onHome={() => setAfter(-1)}
            onNext={() => setAfter(result.steps.at(-1)!.transitionVersion)}
          />
        </>
      )}
    </section>
  );
}
