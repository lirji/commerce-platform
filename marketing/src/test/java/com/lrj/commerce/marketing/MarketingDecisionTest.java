package com.lrj.commerce.marketing;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.marketing.api.*;
import com.lrj.commerce.marketing.api.DecisionModels.*;
import com.lrj.commerce.marketing.application.MarketingDecisionService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketingDecisionTest {
    private final Scope scope = new Scope("t1", "merchant1", "store1");
    private final Instant now = Instant.parse("2026-09-23T00:00:00Z");
    private final Condition member = new Condition.Compare("audience", Condition.Operator.EQ, new Fact.Text("vip"));
    private final Map<String, Fact> facts = Map.of("audience", new Fact.Text("vip"));
    private final DecisionPort service = new MarketingDecisionService();

    @Test void choosesBestOfferAndAllocatesEveryCentDeterministically() {
        var lines = List.of(line("c", 100), line("b", 100), line("a", 100));
        var offers = List.of(offer("z", 2), offer("a", 2), offer("b", 1));
        var quote = service.decide(request(lines, offers, facts, now));
        assertEquals(new Selection("a", 7), quote.selected());
        assertEquals(298, quote.payable().minorUnits());
        assertEquals(List.of(1L, 1L, 0L), quote.lines().stream().map(l -> l.discount().minorUnits()).toList());
        var reversedLines = new ArrayList<>(lines); Collections.reverse(reversedLines);
        var reversedOffers = new ArrayList<>(offers); Collections.reverse(reversedOffers);
        assertEquals(quote, service.decide(request(reversedLines, reversedOffers, facts, now)));
    }

    @Test void allocatesByLargestRemainderRatherThanAlwaysFirstLine() {
        var q = service.decide(request(List.of(line("a", 1), line("b", 2)), List.of(offer("x", 1)), facts, now));
        assertEquals(List.of(0L, 1L), q.lines().stream().map(l -> l.discount().minorUnits()).toList());
    }

    @Test void timeBoundaryIsStartInclusiveEndExclusive() {
        var offer = offer("a", 1);
        var lines = List.of(line("a", 10));
        assertNotNull(service.decide(request(lines, List.of(offer), facts, offer.from())).selected());
        assertNull(service.decide(request(lines, List.of(offer), facts, offer.to())).selected());
        assertEquals(Reason.OUTSIDE_VALIDITY, service.decide(request(lines, List.of(offer), facts, offer.to())).trace().getFirst().reason());
    }

    @Test void missingAudienceCannotQualifyIncludingThroughNot() {
        var negated = new Offer(scope, "a", 1, now.minusSeconds(1), now.plusSeconds(1), Money.ZERO, Money.minor(1), new Condition.Not(member));
        var q = service.decide(request(List.of(line("a", 10)), List.of(negated), Map.of(), now));
        assertNull(q.selected());
        assertEquals(Reason.CONDITION_UNKNOWN, q.trace().getFirst().reason());
    }

    @Test void rejectsDuplicateLinesDuplicateCampaignsAndForeignScopeEvenWhenExpired() {
        var lines = List.of(line("a", 10));
        assertThrows(DomainException.class, () -> service.decide(request(List.of(line("a", 10), line("a", 20)), List.of(), facts, now)));
        assertThrows(DomainException.class, () -> service.decide(request(lines, List.of(offer("a", 1), offer("a", 2)), facts, now)));
        var foreign = new Offer(new Scope("t2", "merchant1", "store1"), "a", 1, now.minusSeconds(10), now.minusSeconds(1), Money.ZERO, Money.minor(1), member);
        assertEquals(DomainException.Code.SCOPE_MISMATCH, assertThrows(DomainException.class,
            () -> service.decide(request(lines, List.of(foreign), facts, now))).code());
    }

    @Test void thresholdCapsDiscountAndHandlesFreeCart() {
        var threshold = new Offer(scope, "a", 1, now.minusSeconds(1), now.plusSeconds(1), Money.minor(10), Money.minor(50), member);
        assertEquals(Reason.BELOW_MINIMUM, service.decide(request(List.of(line("a", 9)), List.of(threshold), facts, now)).trace().getFirst().reason());
        assertEquals(Money.ZERO, service.decide(request(List.of(line("a", 10)), List.of(threshold), facts, now)).payable());
        var free = service.decide(request(List.of(line("a", 0)), List.of(offer("a", 1)), facts, now));
        assertNull(free.selected()); assertEquals(Money.ZERO, free.discount());
    }

    @Test void largeAmountProductsDoNotOverflowAllocation() {
        var q = service.decide(request(List.of(line("a", 40_000_000_000_000L), line("b", 40_000_000_000_000L)),
            List.of(offer("a", 70_000_000_000_001L)), facts, now));
        assertEquals(70_000_000_000_001L, q.lines().stream().mapToLong(l -> l.discount().minorUnits()).sum());
    }

    @Test void randomizedAllocationConservesMoneyAndIsNonNegative() {
        var random = new Random(20260923);
        for (int sample = 0; sample < 500; sample++) {
            var lines = new ArrayList<Line>();
            for (int i = 0, n = 1 + random.nextInt(100); i < n; i++) lines.add(line("l" + i, random.nextInt(100000)));
            var q = service.decide(request(lines, List.of(offer("a", 1 + random.nextInt(5000000))), facts, now));
            assertEquals(q.discount().minorUnits(), q.lines().stream().mapToLong(l -> l.discount().minorUnits()).sum());
            assertEquals(q.payable().minorUnits(), q.lines().stream().mapToLong(l -> l.payable().minorUnits()).sum());
            for (var l : q.lines()) assertTrue(l.payable().minorUnits() >= 0 && l.discount().compareTo(l.gross()) <= 0);
        }
    }

    @Test void snapshotsAndResultsCannotBeMutated() {
        var mutableLines = new ArrayList<>(List.of(line("a", 100)));
        var mutableFacts = new HashMap<>(facts);
        var req = request(mutableLines, List.of(offer("a", 10)), mutableFacts, now);
        mutableLines.clear(); mutableFacts.clear();
        var q = service.decide(req);
        assertEquals(90, q.payable().minorUnits());
        assertThrows(UnsupportedOperationException.class, () -> req.facts().clear());
        assertThrows(UnsupportedOperationException.class, () -> q.lines().clear());
    }

    @Test void rejectsRequestLimitsAndAmountOverflow() {
        assertThrows(DomainException.class, () -> request(Collections.nCopies(101, line("a", 1)), List.of(), facts, now));
        assertThrows(DomainException.class, () -> request(List.of(line("a", 1)), Collections.nCopies(101, offer("a", 1)), facts, now));
        assertThrows(DomainException.class, () -> new Line("a", "sku", Money.minor(1), 10001));
        assertThrows(DomainException.class, () -> service.decide(request(List.of(line("a", 99_999_999_999_999L), line("b", 1)), List.of(), facts, now)));
    }

    private Line line(String id, long cents) { return new Line(id, "sku-" + id, Money.minor(cents), 1); }
    private Offer offer(String id, long cents) { return new Offer(scope, id, 7, now.minusSeconds(10), now.plusSeconds(10), Money.ZERO, Money.minor(cents), member); }
    private Request request(List<Line> lines, List<Offer> offers, Map<String, Fact> inputFacts, Instant at) {
        return new Request(scope, "member1", at, lines, inputFacts, offers);
    }
    @org.junit.jupiter.api.Test void percentageRoundingDoesNotInventAPenny() {
        var percentage=new Offer(scope,"percentage",1,now.minusSeconds(1),now.plusSeconds(1),Money.ZERO,Money.minor(100),member,1);
        var quote=service.decide(request(List.of(line("a",1)),List.of(percentage),facts,now));
        org.junit.jupiter.api.Assertions.assertNull(quote.selected());org.junit.jupiter.api.Assertions.assertEquals(Money.ZERO,quote.discount());
        org.junit.jupiter.api.Assertions.assertEquals(Reason.ELIGIBLE,quote.trace().getFirst().reason());
    }
}
