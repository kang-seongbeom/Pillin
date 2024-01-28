package com.ssafy.yourpilling.pill.model.service.impl;

import com.ssafy.yourpilling.common.TakeWeekday;
import com.ssafy.yourpilling.pill.model.dao.OwnPillDao;
import com.ssafy.yourpilling.pill.model.dao.entity.MonthlyTakerHistory;
import com.ssafy.yourpilling.pill.model.dao.entity.OwnPill;
import com.ssafy.yourpilling.pill.model.dao.entity.PillMember;
import com.ssafy.yourpilling.pill.model.dao.entity.TakerHistory;
import com.ssafy.yourpilling.pill.model.service.OwnPillService;
import com.ssafy.yourpilling.pill.model.service.dto.TakerHistorySummary;
import com.ssafy.yourpilling.pill.model.service.mapper.OwnPillServiceMapper;
import com.ssafy.yourpilling.pill.model.service.mapper.value.OwnPillRegisterValue;
import com.ssafy.yourpilling.pill.model.service.vo.in.*;
import com.ssafy.yourpilling.pill.model.service.vo.out.*;
import com.ssafy.yourpilling.pill.model.service.vo.out.OutOwnPillInventorListVo.ResponsePillInventorListData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.LocalDateTime.now;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OwnOwnPillServiceImpl implements OwnPillService {

    private static final int WEEK = 7;

    private final OwnPillDao ownPillDao;
    private final OwnPillServiceMapper mapper;

    @Override
    public OutOwnPillDetailVo detail(OwnPillDetailVo vo) {
        OwnPill ownPill = ownPillDao.findByOwnPillId(vo.getOwnPillId());
        return mapper.mapToOutOwnPillDetailVo(
                ownPill,
                ownPill.runOutMessage(),
                takeWeekDays(ownPill.getTakeWeekdays()));
    }

    @Override
    public OutOwnPillInventorListVo inventoryList(OwnPillInventoryListVo vo) {
        PillMember member = ownPillDao.findByMemberId(vo.getMemberId());

        Map<Boolean, List<OwnPill>> partition = OwnPill.ownPillsYN(member.getOwnPills());

        return mapper.mapToResponsePillInventorListVo(
                calculationPredicateRunOut(partition.get(true)),
                calculationPredicateRunOut(partition.get(false)));
    }

    @Transactional
    @Override
    public void register(OwnPillRegisterVo vo) {
        OwnPillRegisterValue value = mapToOwnPillRegisterValue(vo);

        ownPillDao.register(mapper.mapToOwnPill(value));
    }

    @Transactional
    @Override
    public void update(OwnPillUpdateVo vo) {
        ownPillDao.update(vo);
    }

    @Transactional
    @Override
    public void remove(OwnPillRemoveVo vo) {
        ownPillDao.removeByOwnPillId(vo.getOwnPillId());
    }

    @Transactional
    @Override
    public OutOwnPillTakeVo take(OwnPillTakeVo ownPillTakeVo) {
        boolean needToUpdate = false;
        OwnPill ownPill = ownPillDao.takeByOwnPillId(ownPillTakeVo.getOwnPillId());

        for(TakerHistory th : ownPill.getTakerHistories()) {
            if(th.getTakeAt().equals(LocalDate.now())) {
                if(th.getCurrentTakeCount() >= th.getNeedToTakeCount()) {
                    throw new IllegalArgumentException("더 이상 복용할 수 없습니다.");
                }


                // if 복용 직후 재고가 다 떨어졌을 때, 일일 복용 기록의 섭취량 컬럼까지 정확하게 카운트할 경우
//                int actualTakeCount = ownPill.decreaseRemains();
                // th.increaseCurrentTakeCount(actualTakeCount);

                // if 복용 직후 재고가 다 떨어졌을 때, 복용 누르면 TakerHistory의 섭취량 컬럼 신경 안쓰고 완료로 할 경우
                ownPill.decreaseRemains();
                th.increaseCurrentTakeCount(ownPill.getTakeOnceAmount());

                if(th.getCurrentTakeCount() >= th.getNeedToTakeCount()) {
                    needToUpdate = true;
                }
                break;
            }
        }


        return OutOwnPillTakeVo
                .builder()
                .needToUpdateWeeklyHistory(needToUpdate)
                .build();
    }

    @Override
    public OutWeeklyTakerHistoryVo weeklyTakerHistory(WeeklyTakerHistoryVo weeklyTakerHistoryVo) {
        return ownPillDao.findWeeklyTakerHistoriesByMemberId(weeklyTakerHistoryVo);
    }

    @Override
    public OutMonthlyTakerHistoryVo monthlyTakerHistory(MonthlyTakerHistoryVo monthlyTakerHistoryVo) {
        List<MonthlyTakerHistory> list = ownPillDao.findMonthlyTakerHistoriesByMemberIdAndDate(monthlyTakerHistoryVo);

        HashMap<LocalDate, TakerHistorySummary> response = new HashMap<>();

        for(MonthlyTakerHistory mth : list) {
            response.putIfAbsent(mth.getTakeAt(), new TakerHistorySummary(0, 0, new ArrayList<MonthlyTakerHistory>()));
            TakerHistorySummary ths = response.get(mth.getTakeAt());
            ths.increaseActualTakenCount(mth.getCurrentTakeCount());
            ths.increaseNeedToTakenCount(mth.getNeedToTakeCount());
            ths.addTakerHistory(mth);
        }

        return OutMonthlyTakerHistoryVo
                .builder()
                .data(response)
                .build();
    }

    private OwnPillRegisterValue mapToOwnPillRegisterValue(OwnPillRegisterVo vo) {
        return OwnPillRegisterValue
                .builder()
                .vo(vo)
                .member(ownPillDao.findByMemberId(vo.getMemberId()))
                .pill(ownPillDao.findByPillId(vo.getPillId()))
                .isAlarm(false)
                .createAt(now())
                .takeWeekDaysValue(TakeWeekday.toValue(vo.getTakeWeekdays()))
                .takeOnceAmount(vo.getTakeOnceAmount())
                .build();
    }

    private ResponsePillInventorListData calculationPredicateRunOut(List<OwnPill> ownPills) {
        List<String> imageUrls = OwnPill.imageUrls(ownPills);
        List<LocalDate> predicateRunOutAts = OwnPill.predicateRunOutAts(ownPills, WEEK);

        if ((ownPills.size() != imageUrls.size()) || (ownPills.size() != predicateRunOutAts.size())) {
            throw new RuntimeException("예상 재고 소진일 계산도중 오류가 발생했습니다.");
        }

        return mapper.mapToResponsePillInventorListData(ownPills, imageUrls, predicateRunOutAts);
    }

    private List<String> takeWeekDays(Integer value) {
        return TakeWeekday.toTakeWeekdays(value);
    }
}
